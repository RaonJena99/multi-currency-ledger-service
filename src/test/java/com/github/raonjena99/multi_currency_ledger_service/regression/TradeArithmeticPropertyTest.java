package com.github.raonjena99.multi_currency_ledger_service.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.github.raonjena99.multi_currency_ledger_service.account.AccountApi;
import com.github.raonjena99.multi_currency_ledger_service.account.application.AccountTradeService;
import com.github.raonjena99.multi_currency_ledger_service.account.application.MonthlyLedgerInitializer;
import com.github.raonjena99.multi_currency_ledger_service.account.application.MonthlyLedgerResolver;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.FxConversion;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.FxDirection;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.FxPricing;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.FxQuote;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.MonthlyAccountLedger;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.event.TradeExecutedEvent;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountRepository;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.FxConversionRepository;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.IdempotencyRecordRepository;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.MonthlyAccountLedgerRepository;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.acl.AccountOutboxAcl;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.CurrencyScaleResolver;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.BelowMinimumNotionalException;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.model.EntryType;
import com.github.raonjena99.multi_currency_ledger_service.common.outbox.OutboxEvent;
import com.github.raonjena99.multi_currency_ledger_service.common.outbox.OutboxRepository;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.LedgerService;
import com.github.raonjena99.multi_currency_ledger_service.transaction.domain.Transaction;
import com.github.raonjena99.multi_currency_ledger_service.transaction.domain.TransactionEntry;
import com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.TransactionRepository;
import com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.acl.OrderToLedgerAcl;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;

/**
 * 거래 산술의 속성 기반 검증: 소수 자릿수가 다른 통화(KRW 0자리, USD 2자리, BHD 3자리, 암호화폐 18자리)를
 * 섞은 무작위 왕복 거래를 고정 시드로 반복해, 반올림을 이용해 돈을 만들 수 없는지와 모든 분개가 대차를 맞추는지 확인한다.
 *
 * <p>실제 {@link AccountTradeService} → {@link AccountOutboxAcl}(JSON 직렬화) → {@link OrderToLedgerAcl} →
 * {@link LedgerService} 를 그대로 구동하고, JPA 저장소만 메모리 구현으로 바꾼다. 분개 대차가 맞지 않으면
 * Kafka 레코드가 DLT 로 빠져 "잔고는 바뀌었는데 분개가 없는" 상태가 되므로, 유효한 거래는 예외 없이 기록되어야 한다.
 */
@DisplayName("속성 기반 회귀 테스트: 거래 산술 (돈 생성 불가 · 분개 대차 일치)")
class TradeArithmeticPropertyTest {

    private static final String MONTH = "2026-09";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final int ROUND_TRIPS_PER_SEED = 1_500;

    /** 1 단위의 USD 가치. 두 통화 사이 환율은 이 값의 비로 만든다. */
    private static final Map<String, BigDecimal> USD_VALUE = Map.of(
            "USD", BigDecimal.ONE,
            "KRW", BigDecimal.ONE.divide(new BigDecimal("1355.7802495361"), 18, RoundingMode.HALF_EVEN),
            "EUR", new BigDecimal("1.0831"),
            "JPY", BigDecimal.ONE.divide(new BigDecimal("149.31"), 18, RoundingMode.HALF_EVEN),
            "BHD", new BigDecimal("2.6525"),
            "BTC", new BigDecimal("84150"));
    private static final String[] BASES = {"KRW", "USD", "JPY", "BHD"};
    private static final String[] PAYMENTS = {"KRW", "USD", "EUR", "JPY", "BHD"};

    @ParameterizedTest(name = "seed={0}")
    @ValueSource(longs = {101L, 202L, 303L})
    @DisplayName("같은 단가로 사고팔면 결제 통화 잔고가 늘지 않고, 모든 분개가 예외 없이 대차를 맞춘다")
    void roundTripNeverCreatesMoneyAndJournalsBalance(long seed) {
        Random random = new Random(seed);
        int executed = 0;

        for (int i = 0; i < ROUND_TRIPS_PER_SEED; i++) {
            String base = pick(random, BASES);
            String payment = pick(random, PAYMENTS);
            boolean crypto = random.nextBoolean();
            String asset = crypto ? "BTC" : pickOtherThan(random, PAYMENTS, payment);
            AssetType assetType = crypto ? AssetType.CRYPTO : AssetType.FIAT;

            BigDecimal market = rate(asset, payment);
            // 시장가 ±2% 안의 임의 단가, 임의 소수 자릿수 (클라이언트가 보낼 수 있는 모양)
            BigDecimal deviation = BigDecimal.valueOf(random.nextDouble() * 0.04 - 0.02);
            BigDecimal price = market.multiply(BigDecimal.ONE.add(deviation))
                    .setScale(random.nextInt(21), RoundingMode.HALF_EVEN);
            if (price.signum() <= 0) {
                continue;
            }
            BigDecimal rawQuantity = crypto
                    ? BigDecimal.valueOf(random.nextDouble()).movePointLeft(random.nextInt(9)).setScale(random.nextInt(19), RoundingMode.DOWN)
                    : BigDecimal.valueOf(random.nextDouble()).movePointRight(random.nextInt(6)).setScale(random.nextInt(7), RoundingMode.DOWN);
            if (rawQuantity.signum() <= 0) {
                continue;
            }
            // 컨트롤러와 같은 방식으로 수량을 자산 단위로 정규화한다.
            Money quantity = Money.of(rawQuantity, assetType, asset);
            BigDecimal fiatToBase = payment.equals(base) ? null : rate(payment, base);

            Env env = new Env(base);
            env.fund(payment, new BigDecimal("1000000000000"));
            BigDecimal fiatBefore = env.balance(payment);
            String context = String.format("seed=%d i=%d base=%s pay=%s asset=%s qty=%s price=%s",
                    seed, i, base, payment, asset, quantity.getAmount().toPlainString(), price.toPlainString());

            try {
                env.service.executeBuyAsset(UUID.randomUUID().toString(), env.accountId, asset, assetType, payment,
                        quantity, price, OffsetDateTime.now(), MONTH, market, false, fiatToBase);
            } catch (BelowMinimumNotionalException tooSmall) {
                continue;
            }
            BigDecimal rawNotional = price.multiply(quantity.getAmount());
            BigDecimal paid = fiatBefore.subtract(env.balance(payment));
            assertThat(paid).as("매수 청구액은 원래 금액을 결제 통화 단위로 올림한 값이다: " + context)
                    .isEqualByComparingTo(rawNotional.setScale(scale(payment), RoundingMode.UP));
            env.consumeLatestLedgerCommand(context);

            try {
                env.service.executeSellAsset(UUID.randomUUID().toString(), env.accountId, asset, assetType, payment,
                        quantity, price, OffsetDateTime.now(), MONTH, market, false, fiatToBase);
            } catch (BelowMinimumNotionalException tooSmall) {
                continue;
            }
            BigDecimal received = env.balance(payment).subtract(fiatBefore.subtract(paid));
            assertThat(received).as("매도 지급액은 원래 금액을 결제 통화 단위로 내림한 값이다: " + context)
                    .isEqualByComparingTo(rawNotional.setScale(scale(payment), RoundingMode.DOWN));
            env.consumeLatestLedgerCommand(context);

            assertThat(env.balance(payment)).as("왕복 거래로 결제 통화 잔고가 늘면 안 된다: " + context)
                    .isLessThanOrEqualTo(fiatBefore);
            assertThat(env.balance(asset)).as("같은 수량을 팔면 자산 잔고는 원래대로 돌아온다: " + context)
                    .isEqualByComparingTo(BigDecimal.ZERO);
            executed++;
        }

        // 최소 주문 금액 미만으로 걸러지는 조합을 빼고도 충분히 많은 왕복이 실제로 체결되어야 의미가 있다.
        assertThat(executed).as("실제로 체결된 왕복 거래 수").isGreaterThan(ROUND_TRIPS_PER_SEED / 2);
    }

    private static final BigDecimal[] FX_SPREADS = {
            new BigDecimal("0.0175"), new BigDecimal("0.02"), new BigDecimal("0.03"), new BigDecimal("0.05")};
    private static final int FX_ROUND_TRIPS_PER_SEED = 1_000;

    @ParameterizedTest(name = "seed={0}")
    @ValueSource(longs = {404L, 505L, 606L})
    @DisplayName("환전 왕복: 같은 시세에서 고객은 이득을 볼 수 없고, 모든 환전 분개가 예외 없이 대차를 맞추며 잔고와 분개가 일치한다")
    void fxRoundTripNeverCreatesMoneyAndJournalsBalance(long seed) {
        Random random = new Random(seed);
        int executed = 0;

        for (int i = 0; i < FX_ROUND_TRIPS_PER_SEED; i++) {
            String base = pick(random, BASES);
            String foreign = pickOtherThan(random, PAYMENTS, base);
            BigDecimal market = rate(foreign, base);
            BigDecimal spread = FX_SPREADS[random.nextInt(FX_SPREADS.length)];
            // 우대율은 0 ~ 0.9 (소수 4자리). 우대가 없는 경우를 자주 섞는다.
            BigDecimal preferential = random.nextInt(3) == 0
                    ? BigDecimal.ZERO : BigDecimal.valueOf(random.nextInt(9001), 4);
            BigDecimal paid = BigDecimal.valueOf(random.nextDouble()).movePointRight(2 + random.nextInt(8))
                    .setScale(scale(base), RoundingMode.DOWN);
            if (paid.signum() <= 0) {
                continue;
            }
            String context = String.format("seed=%d i=%d base=%s foreign=%s paid=%s market=%s spread=%s pref=%s",
                    seed, i, base, foreign, paid.toPlainString(), market.toPlainString(), spread.toPlainString(),
                    preferential.toPlainString());

            Env env = new Env(base);
            BigDecimal funding = new BigDecimal("1000000000000");
            env.fund(base, funding);

            // 1) 기준 통화를 내고 외화를 산다.
            FxPricing.Priced buy = FxPricing.price(FxDirection.BUY_FOREIGN, paid, market, spread, preferential, scale(foreign));
            if (buy.receivedAmount().signum() <= 0) {
                continue;
            }
            FxConversion bought = env.service.executeFxConversion(UUID.randomUUID().toString(),
                    fxQuote(env.accountId, FxDirection.BUY_FOREIGN, base, paid, foreign, buy.receivedAmount(), market,
                            buy.customerRate(), spread, preferential), OffsetDateTime.now(), MONTH);
            env.consumeLatestLedgerCommand(context);
            assertThat(env.balance(foreign)).as("받은 외화: " + context).isEqualByComparingTo(buy.receivedAmount());

            // 2) 받은 외화를 같은 시세로 전부 판다.
            FxPricing.Priced sell = FxPricing.price(FxDirection.SELL_FOREIGN, buy.receivedAmount(), market, spread,
                    preferential, scale(base));
            if (sell.receivedAmount().signum() <= 0) {
                continue;
            }
            FxConversion sold = env.service.executeFxConversion(UUID.randomUUID().toString(),
                    fxQuote(env.accountId, FxDirection.SELL_FOREIGN, foreign, buy.receivedAmount(), base,
                            sell.receivedAmount(), market, sell.customerRate(), spread, preferential),
                    OffsetDateTime.now(), MONTH);
            env.consumeLatestLedgerCommand(context);

            // 같은 시세로 왕복하면 고객은 돈을 벌 수 없다. 손실은 회사가 스프레드로 번 금액과 같다(회사 쪽 시세 가치는
            // 사고 팔 때 내림·올림이라 반올림 한 단위까지 더 벌 수 있다).
            BigDecimal loss = funding.subtract(env.balance(base));
            BigDecimal revenue = bought.getSpreadRevenue().add(sold.getSpreadRevenue());
            assertThat(loss.signum()).as("고객이 왕복 환전으로 이득을 보면 안 된다: " + context).isGreaterThanOrEqualTo(0);
            assertThat(bought.getSpreadRevenue().signum()).as("매수 스프레드 수익: " + context).isGreaterThanOrEqualTo(0);
            assertThat(sold.getSpreadRevenue().signum()).as("매도 스프레드 수익: " + context).isGreaterThanOrEqualTo(0);
            assertThat(revenue).as("회사 수익은 고객 손실 이상, 반올림 한 단위 이내: " + context)
                    .isBetween(loss, loss.add(CurrencyScaleResolver.minimumUnit(AssetType.FIAT, base)));
            assertThat(env.balance(foreign)).as("외화를 모두 팔았다: " + context).isEqualByComparingTo(BigDecimal.ZERO);

            // 잔고와 분개가 같다: 고객 계정의 (통화별) 분개 순증가가 잔고 변화와 일치한다. 이것이 어긋나면 정합성 점검이 잡는다.
            assertThat(journalNet(env, base)).as("기준 통화의 분개 순증가: " + context)
                    .isEqualByComparingTo(env.balance(base).subtract(funding));
            assertThat(journalNet(env, foreign)).as("외화의 분개 순증가: " + context)
                    .isEqualByComparingTo(env.balance(foreign));

            // 반올림 잔차 플러그는 기준 통화 한 단위를 넘지 않아야 한다(계산 오류를 숨기면 안 된다).
            BigDecimal plug = plugTotal(env);
            assertThat(plug).as("반올림 잔차 플러그: " + context)
                    .isLessThanOrEqualTo(CurrencyScaleResolver.minimumUnit(AssetType.FIAT, base));
            executed++;
        }

        assertThat(executed).as("실제로 체결된 환전 왕복 수").isGreaterThan(FX_ROUND_TRIPS_PER_SEED / 2);
    }

    private static FxQuote fxQuote(UUID accountId, FxDirection direction, String from, BigDecimal fromAmount, String to,
                                   BigDecimal toAmount, BigDecimal market, BigDecimal customerRate, BigDecimal spread,
                                   BigDecimal preferential) {
        OffsetDateTime now = OffsetDateTime.now();
        return new FxQuote(UUID.randomUUID(), accountId, direction, from, fromAmount, to, toAmount, market, customerRate,
                spread, preferential, now, now.plusSeconds(30));
    }

    /** 고객 계정의 통화별 분개 순증가(차변 수량 − 대변 수량). 실현 손익 분개는 잔고가 아니므로 뺀다. */
    private static BigDecimal journalNet(Env env, String currency) {
        BigDecimal net = BigDecimal.ZERO;
        for (Transaction transaction : env.journal) {
            for (TransactionEntry entry : transaction.getEntries()) {
                if (!entry.getAccountId().equals(env.accountId) || !entry.getAssetCode().equals(currency)) {
                    continue;
                }
                BigDecimal quantity = entry.getQuantity().getAmount();
                net = entry.getEntryType() == EntryType.DEBIT ? net.add(quantity) : net.subtract(quantity);
            }
        }
        return net;
    }

    /** 반올림 잔차를 흘려보낸 시스템 환차익·환차손 분개의 합. */
    private static BigDecimal plugTotal(Env env) {
        return env.journal.stream().flatMap(t -> t.getEntries().stream())
                .filter(e -> e.getAssetCode().equals("SYSTEM_FX_GAIN") || e.getAssetCode().equals("SYSTEM_FX_LOSS"))
                .map(e -> e.getAmount().getAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal rate(String from, String to) {
        return USD_VALUE.get(from).divide(USD_VALUE.get(to), 18, RoundingMode.HALF_EVEN);
    }

    private static int scale(String fiat) {
        return CurrencyScaleResolver.resolveScale(AssetType.FIAT, fiat);
    }

    private static String pick(Random random, String[] values) {
        return values[random.nextInt(values.length)];
    }

    private static String pickOtherThan(Random random, String[] values, String excluded) {
        String picked;
        do {
            picked = pick(random, values);
        } while (picked.equals(excluded));
        return picked;
    }

    /** 한 계좌의 거래·분개 경로를 실제 서비스로 구성하고, 저장소만 메모리로 대체한다. */
    private static final class Env {
        final UUID accountId = UUID.randomUUID();
        final String base;
        final Map<String, MonthlyAccountLedger> ledgers = new HashMap<>();
        final List<OutboxEvent> outbox = new ArrayList<>();
        final List<Transaction> journal = new ArrayList<>();
        final AccountTradeService service;
        final OrderToLedgerAcl ledgerConsumer;

        Env(String base) {
            this.base = base;
            Account account = Account.open(accountId, "property-test", base);

            MonthlyAccountLedgerRepository ledgerRepository = fake(MonthlyAccountLedgerRepository.class, (name, args) -> switch (name) {
                case "findByAccountIdAndAssetCodeAndLedgerMonth" -> Optional.ofNullable(ledgers.get((String) args[1]));
                case "findFirstWithLockByAccountIdAndAssetCodeAndLedgerMonthLessThanOrderByLedgerMonthDesc" -> Optional.empty();
                case "findLatestLedgerMonthByAccountId" -> ledgers.isEmpty() ? Optional.empty() : Optional.of(MONTH);
                case "save" -> {
                    MonthlyAccountLedger ledger = (MonthlyAccountLedger) args[0];
                    ledgers.put(ledger.getAssetCode(), ledger);
                    yield ledger;
                }
                default -> throw new UnsupportedOperationException(name);
            });
            AccountRepository accountRepository = fake(AccountRepository.class, (name, args) -> switch (name) {
                // 거래는 계좌 상태를 공유 잠금으로 확정한다(해지·정지와의 경쟁 방지).
                case "findById", "findByIdForShare" -> Optional.of(account);
                default -> throw new UnsupportedOperationException(name);
            });
            IdempotencyRecordRepository idempotencyRepository = fake(IdempotencyRecordRepository.class, (name, args) -> switch (name) {
                case "findById" -> Optional.empty();
                case "save", "saveAndFlush" -> args[0];
                default -> throw new UnsupportedOperationException(name);
            });
            OutboxRepository outboxRepository = fake(OutboxRepository.class, (name, args) -> switch (name) {
                case "save" -> {
                    outbox.add((OutboxEvent) args[0]);
                    yield args[0];
                }
                default -> throw new UnsupportedOperationException(name);
            });
            TransactionRepository transactionRepository = fake(TransactionRepository.class, (name, args) -> switch (name) {
                case "existsById" -> false;
                case "save", "saveAndFlush" -> {
                    journal.add((Transaction) args[0]);
                    yield args[0];
                }
                default -> throw new UnsupportedOperationException(name);
            });
            AccountApi accountApi = fake(AccountApi.class, (name, args) -> switch (name) {
                case "getBaseCurrency" -> base;
                default -> throw new UnsupportedOperationException(name);
            });

            MonthlyLedgerResolver resolver = new MonthlyLedgerResolver(ledgerRepository,
                    new MonthlyLedgerInitializer(ledgerRepository, accountRepository));
            AccountOutboxAcl outboxAcl = new AccountOutboxAcl(outboxRepository, MAPPER);
            this.service = new AccountTradeService(event -> {
                if (event instanceof TradeExecutedEvent trade) {
                    outboxAcl.persistOutboxEvent(trade);
                }
            }, accountRepository, idempotencyRepository, ledgerRepository, resolver,
                    fake(FxConversionRepository.class, (name, args) -> switch (name) {
                        case "findByQuoteId" -> Optional.empty();
                        case "save", "saveAndFlush" -> args[0];
                        default -> throw new UnsupportedOperationException(name);
                    }));
            this.ledgerConsumer = new OrderToLedgerAcl(MAPPER,
                    new LedgerService(transactionRepository, accountApi, new SimpleMeterRegistry()));
        }

        void fund(String fiat, BigDecimal amount) {
            ledgers.computeIfAbsent(fiat, code -> MonthlyAccountLedger.initialize(accountId, code, AssetType.FIAT, MONTH, base))
                    .applyAdjustment(Money.of(amount, AssetType.FIAT, fiat));
        }

        BigDecimal balance(String code) {
            MonthlyAccountLedger ledger = ledgers.get(code);
            return ledger == null ? BigDecimal.ZERO : ledger.getBalance().getAmount();
        }

        /** 마지막으로 발행된 원장 커맨드를 Kafka 컨슈머와 같은 경로로 처리하고, 대차를 다시 검증한다. */
        void consumeLatestLedgerCommand(String context) {
            int before = journal.size();
            String payload = outbox.get(outbox.size() - 1).getPayload();
            assertThatCode(() -> ledgerConsumer.consumeLedgerCommand(payload, null))
                    .as("유효한 거래의 분개 기록이 실패하면 DLT 로 빠진다: " + context)
                    .doesNotThrowAnyException();
            assertThat(journal).as("분개가 한 건 기록되어야 한다: " + context).hasSize(before + 1);
            assertThatCode(() -> journal.get(journal.size() - 1).verifyDoubleEntry())
                    .as("분개 대차가 맞아야 한다: " + context)
                    .doesNotThrowAnyException();
        }

        @SuppressWarnings("unchecked")
        private static <T> T fake(Class<T> type, java.util.function.BiFunction<String, Object[], Object> handler) {
            return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> switch (method.getName()) {
                case "toString" -> type.getSimpleName() + "Fake";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> handler.apply(method.getName(), args);
            });
        }
    }
}
