package com.github.raonjena99.multi_currency_ledger_service.account.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.AccountNotFoundException;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.InvalidAccountStateException;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.model.TradeType;
import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 계좌의 자산 매수 및 매도 거래를 처리하는 Facade(중재자) 역할을 수행합니다.
 *
 * <p>DB 트랜잭션과 외부 통신을 분리하는 것이 이 계층의 존재 이유입니다. 환율 조회와 원장 초기화를
 * 트랜잭션 밖에서 먼저 끝내고, 트랜잭션 안에서는 순수 DB 연산만 수행합니다.
 *
 * <p>낙관적 락 재시도는 {@link AccountTradeService} 쪽에 걸려 있습니다. Facade 에 재시도를 걸면
 * 재시도마다 원장 초기화와 환율 조회(외부 HTTP)가 함께 반복되어 거래 1건에 최대 6번의 외부 호출이
 * 발생하고, 시도마다 다른 환율이 적용될 수 있습니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountTradeFacade {

    private final LedgerPeriodResolver periodResolver;
    private final MonthlyLedgerResolver ledgerResolver;
    private final AccountTradeService tradeService;
    private final AccountRepository accountRepository;
    private final ExchangeRateProvider exchangeRateProvider;

    /**
     * 클라이언트가 제시한 단가가 시장 시세에서 벗어날 수 있는 최대 비율입니다.
     * 0 이하로 설정하면 검증을 끕니다.
     */
    @Value("${ledger.trade.max-price-deviation-ratio:0.10}")
    private BigDecimal maxPriceDeviationRatio;

    /**
     * 지정한 결제 통화로 대상 자산을 매수합니다.
     *
     * @param idempotencyKey  중복 요청 방지 키
     * @param accountId       거래 계좌 ID
     * @param targetAssetCode 매수할 자산 코드
     * @param targetAssetType 매수할 자산 유형
     * @param paymentCurrency 결제 통화 코드
     * @param buyQuantity     매수 수량
     * @param unitPrice       결제 통화 기준 매입 단가
     * @return 생성된 거래 ID
     */
    public UUID buyAsset(String idempotencyKey, UUID accountId, String targetAssetCode, AssetType targetAssetType,
                         String paymentCurrency, Money buyQuantity, BigDecimal unitPrice) {

        TradeContext context = prepare(accountId, targetAssetCode, targetAssetType, paymentCurrency, unitPrice, TradeType.BUY);

        return tradeService.executeBuyAsset(idempotencyKey, accountId, targetAssetCode, targetAssetType,
                paymentCurrency, buyQuantity, unitPrice, context.transactedAt(), context.ledgerMonth(),
                context.targetRate(), context.isStaleRate(), context.fiatToBaseRate());
    }

    /**
     * 보유 자산을 지정한 결제 통화로 매도합니다.
     *
     * @param idempotencyKey  중복 요청 방지 키
     * @param accountId       거래 계좌 ID
     * @param targetAssetCode 매도할 자산 코드
     * @param targetAssetType 매도할 자산 유형
     * @param paymentCurrency 수취 통화 코드
     * @param sellQuantity    매도 수량
     * @param sellUnitPrice   수취 통화 기준 매도 단가
     * @return 생성된 거래 ID
     */
    public UUID sellAsset(String idempotencyKey, UUID accountId, String targetAssetCode, AssetType targetAssetType,
                          String paymentCurrency, Money sellQuantity, BigDecimal sellUnitPrice) {

        TradeContext context = prepare(accountId, targetAssetCode, targetAssetType, paymentCurrency, sellUnitPrice, TradeType.SELL);

        return tradeService.executeSellAsset(idempotencyKey, accountId, targetAssetCode, targetAssetType,
                paymentCurrency, sellQuantity, sellUnitPrice, context.transactedAt(), context.ledgerMonth(),
                context.targetRate(), context.isStaleRate(), context.fiatToBaseRate());
    }

    /**
     * 트랜잭션 밖에서 끝내야 하는 준비 작업을 한 번만 수행합니다.
     * 원장 존재 보장, 계좌 조회, 환율 조회, 단가 검증이 여기에 속합니다.
     */
    private TradeContext prepare(UUID accountId, String targetAssetCode, AssetType targetAssetType,
                                 String paymentCurrency, BigDecimal unitPrice, TradeType tradeType) {

        // 자산 코드와 자산 유형의 정합성을 원장 초기화 "이전"에 검증한다.
        // 검증 없이 진행하면 예컨대 (USD, CRYPTO) 조합이 잘못된 유형의 원장 행을 만들고,
        // (account, asset, month) 유니크 제약 때문에 올바른 행을 더는 만들 수 없어
        // 그 달의 해당 자산 거래가 통째로 막힌다.
        validateAssetTypeConsistency(targetAssetCode, targetAssetType, paymentCurrency);

        // 트랜잭션 진입 전 현재 시각 기록. 이후 모든 단계가 이 시각을 공유해야 월 경계에서 흔들리지 않는다.
        OffsetDateTime transactedAt = OffsetDateTime.now();

        // 기장할 실효 원장 월을 계좌 단위로 한 번만 확정한다.
        // 자산 원장과 법정화폐 원장이 서로 다른 월에 놓이면 이후 조회가 실패하고,
        // 읽기 경로가 보는 월보다 과거에 기장하면 거래가 보고 잔고에서 사라진다.
        String ledgerMonth = periodResolver.resolveLedgerMonth(accountId, transactedAt);

        // 1. 트랜잭션 외부에서 원장 존재 여부 보장 (커넥션 풀 데드락 방지)
        ledgerResolver.resolveOrInitializeLedger(accountId, targetAssetCode, targetAssetType, ledgerMonth);
        ledgerResolver.resolveOrInitializeLedger(accountId, paymentCurrency, AssetType.FIAT, ledgerMonth);

        // 2. 외부 API 통신을 DB 트랜잭션 밖에서 수행 (Connection Pool 고갈 방지)
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));

        // 여기서의 상태 확인은 빠른 실패(fail-fast) 목적이다. 확정 검증은 트랜잭션 안에서 다시 수행된다.
        if (!account.isActive()) {
            throw new InvalidAccountStateException("Account is not active for trading: " + accountId);
        }

        String baseCurrency = account.getBaseCurrency();

        var targetRateInfo = exchangeRateProvider.getExchangeRate(targetAssetCode, paymentCurrency);

        BigDecimal fiatToBaseRate = null;
        if (!paymentCurrency.equals(baseCurrency)) {
            fiatToBaseRate = exchangeRateProvider.getExchangeRate(paymentCurrency, baseCurrency).rate();
        }

        validatePriceAgainstMarket(unitPrice, targetRateInfo.rate(), targetAssetCode, paymentCurrency, tradeType);

        return new TradeContext(transactedAt, ledgerMonth, targetRateInfo.rate(), targetRateInfo.isStale(), fiatToBaseRate);
    }

    /**
     * 자산 코드와 클라이언트가 지정한 자산 유형이 서로 모순되지 않는지 검증합니다.
     *
     * <p>클라이언트가 보낸 {@code targetAssetType} 은 신뢰할 수 없는 입력입니다. 검증 없이
     * 저장하면 ISO 통화 코드가 CRYPTO 유형의 원장 행으로 만들어지는 식의 오염이 생기고,
     * 이후 정상 거래가 통화 불일치로 계속 실패합니다.
     */
    private void validateAssetTypeConsistency(String targetAssetCode, AssetType targetAssetType,
                                              String paymentCurrency) {
        if (!isIsoCurrency(paymentCurrency)) {
            throw new com.github.raonjena99.multi_currency_ledger_service.common.exception.UnsupportedAssetCodeException(
                    "결제 통화가 유효한 ISO 4217 코드가 아닙니다: " + paymentCurrency);
        }

        boolean targetIsIsoCurrency = isIsoCurrency(targetAssetCode);
        if (targetAssetType == AssetType.FIAT && !targetIsIsoCurrency) {
            throw new com.github.raonjena99.multi_currency_ledger_service.common.exception.UnsupportedAssetCodeException(
                    "FIAT 자산 코드가 유효한 ISO 4217 코드가 아닙니다: " + targetAssetCode);
        }
        if (targetAssetType != AssetType.FIAT && targetIsIsoCurrency) {
            throw new IllegalArgumentException(String.format(
                    "자산 코드 %s 는 법정화폐(ISO 4217)인데 자산 유형이 %s 로 지정되었습니다.",
                    targetAssetCode, targetAssetType));
        }
    }

    private boolean isIsoCurrency(String code) {
        try {
            java.util.Currency.getInstance(code);
            return true;
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }

    /**
     * 클라이언트가 제시한 단가가 시장 시세에서 크게 벗어나지 않는지 검증합니다.
     *
     * <p>이 검증이 없으면 클라이언트가 임의 가격으로 매수·매도할 수 있습니다. 조회한 시세를
     * 이벤트에 기록만 하고 검증에 쓰지 않으면 시세 조회 자체가 무의미해집니다.
     */
    private void validatePriceAgainstMarket(BigDecimal unitPrice, BigDecimal marketRate,
                                            String assetCode, String paymentCurrency, TradeType tradeType) {
        if (maxPriceDeviationRatio == null || maxPriceDeviationRatio.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        if (marketRate == null || marketRate.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("시장 시세를 확보하지 못해 단가 검증을 건너뜁니다. asset={}, payment={}", assetCode, paymentCurrency);
            return;
        }

        BigDecimal deviation = unitPrice.subtract(marketRate).abs()
                .divide(marketRate, 18, RoundingMode.HALF_EVEN);

        if (deviation.compareTo(maxPriceDeviationRatio) > 0) {
            throw new IllegalArgumentException(String.format(
                    "%s 단가 %s %s 가 시장 시세 %s 에서 %s%% 벗어났습니다. 허용 범위는 %s%% 입니다.",
                    tradeType, unitPrice.toPlainString(), paymentCurrency, marketRate.toPlainString(),
                    deviation.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP).toPlainString(),
                    maxPriceDeviationRatio.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString()));
        }
    }

    /**
     * 트랜잭션 밖에서 확정된, 거래 실행에 필요한 입력값 묶음입니다.
     *
     * @param transactedAt   거래 기준 시각
     * @param ledgerMonth    기장 대상 실효 원장 월
     * @param targetRate     대상 자산 → 결제 통화 환율
     * @param isStaleRate    환율이 지연 데이터인지 여부
     * @param fiatToBaseRate 결제 통화 → 기준 통화 환율. 두 통화가 같으면 null
     */
    private record TradeContext(OffsetDateTime transactedAt, String ledgerMonth, BigDecimal targetRate,
                                boolean isStaleRate, BigDecimal fiatToBaseRate) {}
}
