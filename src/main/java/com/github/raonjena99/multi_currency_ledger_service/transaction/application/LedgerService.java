package com.github.raonjena99.multi_currency_ledger_service.transaction.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.github.raonjena99.multi_currency_ledger_service.account.AccountApi;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.CurrencyScaleResolver;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.DoubleEntryImbalanceException;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.model.EntryType;
import com.github.raonjena99.multi_currency_ledger_service.transaction.application.command.LedgerRecordingCommand;
import com.github.raonjena99.multi_currency_ledger_service.transaction.domain.Transaction;
import com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.TransactionRepository;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * 복식 부기 원장 기록 로직을 담당하는 LedgerService(원장 서비스) 클래스입니다.
 */
@Slf4j
@Service
public class LedgerService {

    private static final UUID SYSTEM_FEE_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SYSTEM_ACCOUNT_ID = new UUID(0, 0);

    private final TransactionRepository transactionRepository;

    /**
     * 수수료 보정(FEE_ADJUSTMENT)을 고객의 실제 잔고에 반영하기 위한 계좌 모듈 공개 API.
     *
     * <p>보정을 분개로만 남기면 고객이 쓸 수 있는 잔고(월차 원장)는 변하지 않아,
     * 분개 합계와 잔고 합계가 영구히 벌어집니다. 분개와 잔고 반영이 같은 트랜잭션에
     * 묶여야 부분 실패가 불가능합니다.
     */
    private final AccountApi accountApi;

    /**
     * 시스템 계정으로 흘려보낸 반올림 잔차의 분포.
     *
     * <p>허용 한도는 환율 증폭을 반영해야 하므로 필연적으로 넓어집니다. 그 안에 실제 계산 버그가
     * 숨을 수 있으므로, 플러그 규모를 지표로 노출해 추세가 커지는지 감시합니다.
     * 정상 운영에서는 통화 최소 단위 수준에 머물러야 합니다.
     */
    private final DistributionSummary plugMagnitude;

    public LedgerService(TransactionRepository transactionRepository, AccountApi accountApi, MeterRegistry meterRegistry) {
        this.transactionRepository = transactionRepository;
        this.accountApi = accountApi;
        this.plugMagnitude = DistributionSummary.builder("ledger.rounding_residual.plugged")
                .description("SYSTEM_FX_GAIN/LOSS 로 흘려보낸 대차 잔차의 크기(기준 통화). "
                        + "추세가 커지면 반올림이 아닌 계산 오류를 의심해야 합니다.")
                .publishPercentiles(0.5, 0.95, 1.0)
                .register(meterRegistry);
    }

    /**
     * 반올림만으로 설명 가능한 최대 대차 차액을 계산합니다.
     *
     * <p>단순히 "엔트리 수 × 기준 통화 최소 단위" 로 두면 외화 거래가 통째로 실패합니다.
     * 결제 통화에서 발생한 반올림 오차가 <b>환율을 타고 증폭</b>되기 때문입니다.
     * 예: USD 최소 단위 0.01 이 환율 1300 을 만나면 기준 통화에서 13 KRW 오차가 됩니다.
     *
     * <p>그래서 엔트리별로 두 종류의 반올림을 각각 더합니다.
     * <ul>
     *   <li>엔트리 수량 통화의 최소 단위 × 적용 환율 — 결제 통화 정규화 오차가 증폭된 몫</li>
     *   <li>기준 통화의 최소 단위 — 기준 통화로 환산해 저장할 때의 오차</li>
     * </ul>
     * 매수는 UP, 매도는 DOWN 으로 방향성 반올림을 하므로 오차 상한은 0.5 단위가 아니라
     * <b>1 최소 단위</b>입니다.
     */
    private BigDecimal allowedRoundingResidual(Transaction transaction, String baseCurrency) {
        BigDecimal baseMinUnit = CurrencyScaleResolver.minimumUnit(AssetType.FIAT, baseCurrency);
        BigDecimal allowed = BigDecimal.ZERO;

        for (var entry : transaction.getEntries()) {
            BigDecimal rate = entry.getExchangeRate() != null
                    ? entry.getExchangeRate().abs() : BigDecimal.ONE;

            BigDecimal quantityMinUnit = CurrencyScaleResolver.minimumUnit(
                    entry.getQuantity().getAssetType(), entry.getQuantity().getCurrencyCode());

            allowed = allowed
                    .add(quantityMinUnit.multiply(rate))
                    .add(baseMinUnit);
        }
        return allowed;
    }

    /**
     * 복식 부기 원장 기록을 수행합니다.
     *
     * @param cmd 원장 기록을 위한 LedgerRecordingCommand(명령) 객체
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDoubleEntry(LedgerRecordingCommand cmd) {
        // 거래 ID로 중복 기록 여부를 확인하여 멱등성(Idempotency)을 보장합니다.
        if (transactionRepository.existsById(cmd.referenceTradeId())) {
            log.warn("Ledger already recorded for TradeID: {}. Ignoring duplicate request.", cmd.referenceTradeId());
            return;
        }

        String description = "Auto-recorded via ACL. Ref TradeID: " + cmd.referenceTradeId();
        if (cmd.isStaleRate()) {
            description += " [APPLIED_FALLBACK_RATE=TRUE]";
            log.warn("Fallback 환율이 적용된 거래가 원장에 기록됩니다. 향후 정산 대사(Reconciliation) 시 오차 허용 룰 엔진의 타겟이 됩니다. TradeID: {}", cmd.referenceTradeId());
        }

        // 원장의 거래 시각은 소비 시각이 아니라 실제 거래 시각을 따라야 월차 원장 귀속월과 일치한다.
        Transaction transaction = Transaction.record(
            cmd.referenceTradeId(),
            cmd.tradeType(),
            description,
            cmd.transactedAt()
        );

        String baseCurrency = cmd.baseCurrency() != null ? cmd.baseCurrency() : cmd.fiatCode();

        // 거래 시점에 실제로 적용된 환율을 그대로 사용한다.
        //
        // 여기서 환율을 다시 조회하면 (1) 잔고에 적용된 환율과 원장에 기록되는 환율이 달라지고,
        // (2) Kafka 재시도마다 값이 바뀌어 같은 거래가 시도마다 다른 금액으로 기록되며,
        // (3) 트랜잭션 안에서 외부 HTTP 를 호출해 DB 커넥션을 네트워크 I/O 동안 붙잡게 된다.
        BigDecimal fiatToBaseRate = cmd.fiatToBaseRate() != null ? cmd.fiatToBaseRate() : BigDecimal.ONE;

        // 매수(BUY) 거래인 경우: 자산을 매수하고 법정화폐를 매도(지불)합니다.
        if ("BUY".equals(cmd.tradeType())) {
            BigDecimal requiredFiatAmount = cmd.unitPrice().multiply(cmd.quantity().getAmount());
            // 반올림 방향은 AccountTradeService 가 잔고에 적용한 것과 반드시 같아야 한다.
            // 여기서 HALF_EVEN 을 쓰면 원장이 잔고와 다른 금액을 기록한다.
            // (0.505 USD → 잔고는 UP 으로 0.51 을 차감하는데 원장은 0.50 을 기록)
            Money fiatMoney = Money.of(requiredFiatAmount, AssetType.FIAT, cmd.fiatCode(), RoundingMode.UP);

            // 차변(Debit): 매수한 자산 증가 기록
            transaction.addBuyEntry(cmd.accountId(), cmd.assetCode(), cmd.quantity(), cmd.unitPrice(), fiatToBaseRate, baseCurrency);
            // 대변(Credit): 지불한 법정화폐 감소 기록
            // averageCost 는 기준 통화 단위여야 한다. 결제 통화 1단위는 기준 통화로 rate 이다.
            // 여기에 ONE 을 넘기면 (1×rate − 1)×qty 만큼 존재하지 않는 실현손익이 생성된다.
            transaction.addSellEntry(cmd.accountId(), cmd.fiatCode(), fiatMoney,
                                    BigDecimal.ONE, fiatToBaseRate, fiatToBaseRate, baseCurrency);
        } else if ("SELL".equals(cmd.tradeType())) {
            // 매도(SELL) 거래인 경우: 법정화폐를 매수(수취)하고 자산을 매도합니다.
            BigDecimal earnedFiatAmount = cmd.unitPrice().multiply(cmd.quantity().getAmount());
            // 고객이 수취하는 금액이므로 잔고와 동일하게 DOWN 으로 정규화한다.
            Money fiatMoney = Money.of(earnedFiatAmount, AssetType.FIAT, cmd.fiatCode(), RoundingMode.DOWN);

            // 차변(Debit): 수취한 법정화폐 증가 기록
            transaction.addBuyEntry(cmd.accountId(), cmd.fiatCode(), fiatMoney,
                                    BigDecimal.ONE, fiatToBaseRate, baseCurrency);
            // 대변(Credit): 매도한 자산 감소 기록
            transaction.addSellEntry(cmd.accountId(), cmd.assetCode(), cmd.quantity(),
                                    cmd.unitPrice(), fiatToBaseRate, cmd.averageCost(), baseCurrency);
        } else if ("FEE_DEDUCTION".equals(cmd.tradeType())) {
            // 수수료 차감: 고객 계좌에서 차감하고 시스템 수수료 계정으로 귀속
            transaction.addSellEntry(cmd.accountId(), cmd.assetCode(), cmd.quantity(),
                                    cmd.unitPrice(), fiatToBaseRate,
                                    cmd.averageCost() != null
                                            ? cmd.averageCost()
                                            // 폴백값은 결제 통화 단가이므로 기준 통화로 환산해야 단위가 맞는다.
                                            : cmd.unitPrice().multiply(fiatToBaseRate),
                                    baseCurrency);

            transaction.addBuyEntry(SYSTEM_FEE_ACCOUNT_ID, cmd.assetCode(), cmd.quantity(),
                                    cmd.unitPrice(), fiatToBaseRate, baseCurrency);
        } else if ("FEE_ADJUSTMENT".equals(cmd.tradeType())) {

            // 정산 수수료 오차 반영. cmd.accountId() 는 오차가 귀속되는 실제 고객 계좌다.
            if (cmd.quantity().getAmount().compareTo(BigDecimal.ZERO) > 0) {
                // 초과 수취에 대한 환불: 차변(고객 법정화폐 입금), 대변(시스템 법정화폐 출금)
                transaction.addBuyEntry(cmd.accountId(), cmd.fiatCode(), cmd.quantity(),
                                        BigDecimal.ONE, fiatToBaseRate, baseCurrency);

                transaction.addSellEntry(SYSTEM_FEE_ACCOUNT_ID, cmd.fiatCode(), cmd.quantity(),
                                        BigDecimal.ONE, fiatToBaseRate,
                                        fiatToBaseRate, baseCurrency);
            } else if (cmd.quantity().getAmount().compareTo(BigDecimal.ZERO) < 0) {
                // 초과 지불에 대한 추가 징수: 차변(시스템 법정화폐 입금), 대변(고객 법정화폐 출금)
                Money lossAmount = Money.of(cmd.quantity().getAmount().abs().toPlainString(), AssetType.FIAT, cmd.fiatCode());

                transaction.addBuyEntry(SYSTEM_FEE_ACCOUNT_ID, cmd.fiatCode(), lossAmount,
                                        BigDecimal.ONE, fiatToBaseRate, baseCurrency);

                transaction.addSellEntry(cmd.accountId(), cmd.fiatCode(), lossAmount,
                                        BigDecimal.ONE, fiatToBaseRate,
                                        fiatToBaseRate, baseCurrency);
            }

            // 보정액을 고객의 실제 잔고(월차 원장)에도 반영한다. 분개만 남기면 고객이 쓸 수 있는
            // 잔고는 변하지 않아 SUM(분개)와 SUM(잔고)가 영구히 벌어진다. 같은 트랜잭션이므로
            // 위쪽의 거래 ID 멱등성 검사가 중복 반영도 함께 막는다.
            accountApi.applyFiatBalanceAdjustment(cmd.accountId(), cmd.quantity(), cmd.transactedAt());
        } else {
            throw new IllegalArgumentException("Unsupported trade type for ledger recording: " + cmd.tradeType());
        }

        plugRoundingResidual(transaction, baseCurrency);

        // 애그리거트 불변식을 저장 전에 명시적으로 검증한다. JPA 콜백(@PreUpdate)은 부모 행이
        // dirty 하지 않으면 발동하지 않으므로 콜백에만 의존하면 검증이 조용히 건너뛰어진다.
        transaction.verifyDoubleEntry();

        // Unique Constraint 를 활용한 DB 락 멱등성 보장.
        // 주의: 여기서 DataIntegrityViolationException 을 catch 하고 삼키면 트랜잭션이
        // rollback-only 로 마킹되어 UnexpectedRollbackException 이 발생한다.
        // 예외를 그대로 올려 Kafka 재시도에 맡기는 것이 분산 시스템의 정석이다.
        transactionRepository.saveAndFlush(transaction);

        log.info("Ledger successfully recorded for TradeID: {}", cmd.referenceTradeId());
    }

    /**
     * 통화 스케일 정규화에서 생기는 잔여 오차만 시스템 계정으로 흘려보냅니다.
     *
     * 이 플러그는 <b>반올림 잔차 전용</b>입니다. 예전 구현은 차액을 무제한으로 흡수해
     * {@code verifyDoubleEntry()} 가 구조적으로 실패할 수 없게 만들었고, 그 결과 실제 계산 버그가
     * 시스템 환차손익으로 조용히 숨었습니다. 그래서 허용 한도를 두고 초과분은 예외로 올립니다.
     */
    private void plugRoundingResidual(Transaction transaction, String baseCurrency) {
        BigDecimal totalDebit = transaction.getEntries().stream()
            .filter(e -> e.getEntryType() == EntryType.DEBIT && e.getAmount().getCurrencyCode().equals(baseCurrency))
            .map(e -> e.getAmount().getAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalCredit = transaction.getEntries().stream()
            .filter(e -> e.getEntryType() == EntryType.CREDIT && e.getAmount().getCurrencyCode().equals(baseCurrency))
            .map(e -> e.getAmount().getAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        // (매도 시) 대변에 함께 기록된 실현 손익(Realized PnL)도 대변 합계에 합산
        BigDecimal totalRealizedPnl = transaction.getEntries().stream()
            .filter(e -> e.getRealizedPnl() != null && !e.getRealizedPnl().isZero())
            .map(e -> e.getRealizedPnl().getAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal difference = totalDebit.subtract(totalCredit.add(totalRealizedPnl));
        if (difference.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }

        BigDecimal allowedResidual = allowedRoundingResidual(transaction, baseCurrency);

        if (difference.abs().compareTo(allowedResidual) > 0) {
            throw new DoubleEntryImbalanceException(String.format(
                "Imbalance %s %s exceeds the rounding allowance %s. This is a calculation error, not a rounding residual. "
                    + "(Debit: %s, Credit incl. PnL: %s)",
                difference.toPlainString(), baseCurrency, allowedResidual.toPlainString(),
                totalDebit.toPlainString(), totalCredit.add(totalRealizedPnl).toPlainString()));
        }

        // 플러그 금액을 지표로 노출한다. 허용 한도를 넓히면 그 안에 실제 계산 버그가 숨을 수 있으므로,
        // 누적 플러그 규모를 관측 가능하게 만드는 것이 유일한 실질적 방어선이다.
        plugMagnitude.record(difference.abs().doubleValue());

        if (difference.compareTo(BigDecimal.ZERO) > 0) {
            // 차변 > 대변: 시스템 환차익 발생 -> 대변(Sell) 엔트리로 SYSTEM_FX_GAIN 추가
            Money differenceMoney = Money.of(difference.toPlainString(), AssetType.FIAT, baseCurrency);
            transaction.addSellEntry(SYSTEM_ACCOUNT_ID, "SYSTEM_FX_GAIN", differenceMoney,
                                    BigDecimal.ONE, BigDecimal.ONE,
                                    BigDecimal.ONE, baseCurrency);
        } else {
            // 차변 < 대변: 시스템 환차손 발생 -> 차변(Buy) 엔트리로 SYSTEM_FX_LOSS 추가
            Money differenceMoney = Money.of(difference.abs().toPlainString(), AssetType.FIAT, baseCurrency);
            transaction.addBuyEntry(SYSTEM_ACCOUNT_ID, "SYSTEM_FX_LOSS", differenceMoney,
                                    BigDecimal.ONE, BigDecimal.ONE, baseCurrency);
        }
    }
}
