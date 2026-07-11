package com.github.raonjena99.multi_currency_ledger_service.account.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.MonthlyAccountLedger;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.event.TradeExecutedEvent;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.port.ExchangeRateProvider;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Account(계좌)의 자산 매수 및 매도 거래를 처리하는 Service(서비스) 클래스입니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountTradeService {
    
    private final MonthlyLedgerResolver ledgerResolver; 
    private final ApplicationEventPublisher eventPublisher;
    private final ExchangeRateProvider exchangeRateProvider;

    private static final String FIAT_CODE = "KRW";

    /**
     * 특정 Account(계좌)에서 자산을 매수(Buy)하는 처리를 수행합니다.
     * 법정 화폐(KRW) 잔고를 차감하고 대상 자산의 잔고와 평균 단가를 갱신한 뒤, TradeExecutedEvent(이벤트)를 발행합니다.
     *
     * @param accountId 계좌 ID
     * @param targetAssetCode 매수할 대상 자산 코드
     * @param targetAssetType 매수할 대상 자산 유형
     * @param buyQuantity 매수 수량
     * @param unitPrice 매입 단가
     * @return 생성된 거래의 고유 식별자(Trade ID)
     */
    @Transactional
    public UUID buyAsset(UUID accountId, String targetAssetCode, AssetType targetAssetType, 
                        Money buyQuantity, Money unitPrice) {
        
        BigDecimal exchangeRate = BigDecimal.ONE;
        OffsetDateTime transactedAt = OffsetDateTime.now();

        // 매수할 자산과 결제 수단인 법정 화폐에 대한 당월 MonthlyAccountLedger(월별 계좌 원장) 조회 혹은 초기화
        MonthlyAccountLedger targetAssetLedger = ledgerResolver.resolveOrInitializeLedger(accountId, targetAssetCode, targetAssetType, transactedAt);
        MonthlyAccountLedger fiatLedger = ledgerResolver.resolveOrInitializeLedger(accountId, FIAT_CODE, AssetType.FIAT, transactedAt);

        // 결제에 필요한 법정 화폐 금액 계산 = 매입 단가 * 매수 수량
        Money requiredFiatAmount = unitPrice.multiply(buyQuantity.getAmount()); 

        // Version 필드를 활용한 낙관적 락(Optimistic Lock) 작동으로 동시성 제어
        fiatLedger.subtractBalance(requiredFiatAmount);
        targetAssetLedger.addBalance(buyQuantity, unitPrice);

        UUID tradeId = UUID.randomUUID();

        var rateInfo = exchangeRateProvider.getExchangeRate(targetAssetCode, FIAT_CODE);

        // 잔고 반영 후 거래 성공 이벤트 생성 및 발행
        TradeExecutedEvent event = new TradeExecutedEvent(
            tradeId, accountId, targetAssetCode, targetAssetType, FIAT_CODE, com.github.raonjena99.multi_currency_ledger_service.common.model.TradeType.BUY, 
            buyQuantity.getAmount(), unitPrice.getAmount(), exchangeRate, BigDecimal.ZERO,
            rateInfo.isStale(), OffsetDateTime.now()
        );
        eventPublisher.publishEvent(event);
        
        log.info("Monthly Ledger updated for BUY. TradeID: {}", tradeId);
        return tradeId;
    }

    /**
     * 특정 Account(계좌)에서 보유 자산을 매도(Sell)하는 처리를 수행합니다.
     * 대상 자산의 잔고를 차감하고, 그에 따른 법정 화폐(KRW) 수익을 잔고에 반영한 뒤 TradeExecutedEvent(이벤트)를 발행합니다.
     *
     * @param accountId 계좌 ID
     * @param targetAssetCode 매도할 대상 자산 코드
     * @param targetAssetType 매도할 대상 자산 유형
     * @param sellQuantity 매도 수량
     * @param sellUnitPrice 매도 단가
     * @return 생성된 거래의 고유 식별자(Trade ID)
     */
    @Transactional
    public UUID sellAsset(UUID accountId, String targetAssetCode, AssetType targetAssetType, 
                        Money sellQuantity, Money sellUnitPrice) {
        
        OffsetDateTime transactedAt = OffsetDateTime.now();
        
        // 매도할 자산과 수익금을 반영할 법정 화폐에 대한 당월 MonthlyAccountLedger(월별 계좌 원장) 조회 혹은 초기화
        MonthlyAccountLedger targetAssetLedger = ledgerResolver.resolveOrInitializeLedger(accountId, targetAssetCode, targetAssetType, transactedAt);
        MonthlyAccountLedger fiatLedger = ledgerResolver.resolveOrInitializeLedger(accountId, FIAT_CODE, AssetType.FIAT, transactedAt);

        // 매도 자산 잔고 차감 및 당시 평균 단가 계산
        Money averageCost = targetAssetLedger.subtractBalance(sellQuantity);
        
        // 매도로 획득한 법정 화폐 수익금 계산 = 매도 수량 * 매도 단가
        Money earnedFiatAmount = Money.of(
            sellQuantity.getAmount().multiply(sellUnitPrice.getAmount()).toPlainString(),
            AssetType.FIAT,
            FIAT_CODE
        );
        // 수익금을 법정 화폐 원장에 반영 (법정 화폐이므로 단가는 1)
        fiatLedger.addBalance(earnedFiatAmount, Money.of("1", AssetType.FIAT, FIAT_CODE));

        UUID tradeId = UUID.randomUUID();

        var rateInfo = exchangeRateProvider.getExchangeRate(targetAssetCode, FIAT_CODE);

        // 잔고 반영 후 거래 성공 이벤트 생성 및 발행
        TradeExecutedEvent event = new TradeExecutedEvent(
            tradeId, accountId, targetAssetCode, targetAssetType, FIAT_CODE, com.github.raonjena99.multi_currency_ledger_service.common.model.TradeType.SELL, 
            sellQuantity.getAmount(), sellUnitPrice.getAmount(), rateInfo.rate(), averageCost.getAmount(),
            rateInfo.isStale(), OffsetDateTime.now()
        );

        eventPublisher.publishEvent(event);
        
        log.info("Monthly Ledger updated for SELL. TradeID: {}", tradeId);
        return tradeId;
    }
}