package com.github.raonjena99.multi_currency_ledger_service.account.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.IdempotencyRecord;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.MonthlyAccountLedger;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.event.TradeExecutedEvent;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountRepository;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.IdempotencyRecordRepository;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.MonthlyAccountLedgerRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.CurrencyScaleResolver;
import com.github.raonjena99.multi_currency_ledger_service.common.domain.Money;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.AccountNotFoundException;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.BelowMinimumNotionalException;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.DuplicateTradeRequestException;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.InvalidAccountStateException;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.model.TradeType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Account(계좌)의 자산 매수 및 매도 거래를 처리하는 Service(서비스) 클래스입니다.
 *
 * <p>낙관적 락 재시도가 이 계층에 걸려 있습니다. Spring Retry 의 어드바이스가 트랜잭션
 * 어드바이스보다 바깥에 적용되므로 재시도마다 새 트랜잭션이 시작되고, 멱등성 키 삽입도 함께
 * 롤백된 뒤 다시 수행됩니다. 외부 HTTP 호출은 Facade 가 이미 끝냈으므로 재시도가 반복하지 않습니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountTradeService {


    private final ApplicationEventPublisher eventPublisher;
    private final AccountRepository accountRepository;
    private final IdempotencyRecordRepository idempotencyRepository;
    private final MonthlyAccountLedgerRepository monthlyAccountLedgerRepository;
    private final MonthlyLedgerResolver monthlyLedgerResolver;

    /**
     * 특정 Account(계좌)에서 자산을 매수(Buy)하는 처리를 수행합니다.
     * 지정된 결제 통화(paymentCurrency) 잔고를 차감하고 대상 자산의 잔고와 평균 단가를 갱신한 뒤, TradeExecutedEvent(이벤트)를 발행합니다.
     *
     * @param idempotencyKey 중복 방지를 위한 키
     * @param accountId 계좌 ID
     * @param targetAssetCode 매수할 대상 자산 코드
     * @param targetAssetType 매수할 대상 자산 유형
     * @param paymentCurrency 결제에 사용할 법정 화폐 코드
     * @param buyQuantity 매수 수량
     * @param unitPrice 매입 단가
     * @param transactedAt 거래 기준 시각
     * @param ledgerMonth 기장 대상 실효 원장 월 (yyyy-MM)
     * @param exchangeRate 대상 자산 → 결제 통화 환율 (이벤트 기록용)
     * @param isStaleRate 적용된 환율이 지연 데이터인지 여부
     * @param fiatToBaseRate 결제 통화 → 기준 통화 환율. 두 통화가 같으면 null
     * @return 생성된 거래의 고유 식별자
     */
    @Retryable(
        retryFor = OptimisticLockingFailureException.class,
        maxAttempts = 3,
        backoff = @Backoff(delay = 100, multiplier = 2.0)
    )
    @Transactional
    public UUID executeBuyAsset(String idempotencyKey, UUID accountId, String targetAssetCode, AssetType targetAssetType,
                                String paymentCurrency, Money buyQuantity, BigDecimal unitPrice, OffsetDateTime transactedAt, String ledgerMonth,
                                BigDecimal exchangeRate, boolean isStaleRate, BigDecimal fiatToBaseRate) {

        IdempotencyOutcome idempotency = registerIdempotencyKey(
                accountId, "BUY", idempotencyKey, "이미 처리 중인 결제 요청입니다.");
        if (idempotency.replayedTradeId() != null) {
            return idempotency.replayedTradeId();
        }

        // 계좌 상태를 트랜잭션 안에서 다시 확정 검증한다.
        // Facade 의 사전 검사만 믿으면 검사와 실제 거래 사이에 계좌가 정지되어도 거래가 완료된다(TOCTOU).
        requireActiveAccount(accountId);

        // 기장 월은 Facade 가 확정한 값을 기본으로 하되, 트랜잭션 안에서 최신 월을 재확인한다.
        // Facade 의 확정과 이 트랜잭션의 커밋 사이에 다른 거래가 다음 달 원장을 만들었으면(월 경계 경합)
        // 이 거래를 이전 달에 기장하는 순간 조회 경로(MAX(ledger_month))에서 사라진다.
        String effectiveMonth = resolveEffectiveMonth(accountId, ledgerMonth);
        MonthlyAccountLedger targetAssetLedger = monthlyLedgerResolver
                .resolveOrInitializeLedger(accountId, targetAssetCode, targetAssetType, effectiveMonth);
        MonthlyAccountLedger fiatLedger = monthlyLedgerResolver
                .resolveOrInitializeLedger(accountId, paymentCurrency, AssetType.FIAT, effectiveMonth);

        // 결제 단가를 계좌의 기준 통화로 환산해 평균 단가를 일관된 통화로 유지한다.
        BigDecimal appliedFiatToBaseRate = fiatToBaseRate != null ? fiatToBaseRate : BigDecimal.ONE;
        BigDecimal unitPriceInBaseCurrency = unitPrice.multiply(appliedFiatToBaseRate);

        // 결제에 필요한 법정 화폐 금액 = 매입 단가 * 매수 수량.
        // 고객이 지불하는 금액이므로 UP 으로 정규화해 청구액이 깎이지 않게 한다.
        BigDecimal requiredFiatRaw = unitPrice.multiply(buyQuantity.getAmount());
        requireAboveMinimumNotional(requiredFiatRaw, paymentCurrency, "매수 대금");
        Money requiredFiatAmount = Money.of(requiredFiatRaw, AssetType.FIAT, paymentCurrency, RoundingMode.UP);

        // Version 필드를 활용한 낙관적 락(Optimistic Lock) 작동으로 동시성 제어
        fiatLedger.subtractBalance(requiredFiatAmount);
        targetAssetLedger.addBalance(buyQuantity, unitPriceInBaseCurrency);

        monthlyAccountLedgerRepository.save(fiatLedger);
        monthlyAccountLedgerRepository.save(targetAssetLedger);

        UUID tradeId = UUID.randomUUID();

        // 잔고 반영 후 거래 성공 이벤트 생성 및 발행
        TradeExecutedEvent event = new TradeExecutedEvent(
            tradeId, accountId, targetAssetCode, targetAssetType, paymentCurrency,
            targetAssetLedger.getBaseCurrency(),
            TradeType.BUY,
            buyQuantity.getAmount(), unitPrice, exchangeRate, appliedFiatToBaseRate, BigDecimal.ZERO,
            isStaleRate, transactedAt
        );
        eventPublisher.publishEvent(event);

        // 완료된 거래 ID 를 멱등성 레코드에 기록한다(같은 트랜잭션이므로 원자적).
        // 이 기록이 있어야 타임아웃 후 재전송된 동일 요청이 409 대신 기존 거래 ID 를 돌려받는다.
        idempotency.record().complete(tradeId);

        log.info("Monthly Ledger updated for BUY. TradeID: {}", tradeId);
        return tradeId;
    }

    /**
     * 특정 Account(계좌)에서 보유 자산을 매도(Sell)하는 처리를 수행합니다.
     * 대상 자산의 잔고를 차감하고, 그에 따른 지정된 법정 화폐 수익을 잔고에 반영한 뒤 TradeExecutedEvent(이벤트)를 발행합니다.
     *
     * @param idempotencyKey 중복 방지를 위한 키
     * @param accountId 계좌 ID
     * @param targetAssetCode 매도할 대상 자산 코드
     * @param targetAssetType 매도할 대상 자산 유형
     * @param paymentCurrency 결제로 받을 법정 화폐 코드
     * @param sellQuantity 매도 수량
     * @param sellUnitPrice 매도 단가
     * @param transactedAt 거래 기준 시각
     * @param ledgerMonth 기장 대상 실효 원장 월 (yyyy-MM)
     * @param exchangeRate 대상 자산 → 결제 통화 환율 (이벤트 기록용)
     * @param isStaleRate 적용된 환율이 지연 데이터인지 여부
     * @param fiatToBaseRate 결제 통화 → 기준 통화 환율. 두 통화가 같으면 null
     * @return 생성된 거래의 고유 식별자
     */
    @Retryable(
        retryFor = OptimisticLockingFailureException.class,
        maxAttempts = 3,
        backoff = @Backoff(delay = 100, multiplier = 2.0)
    )
    @Transactional
    public UUID executeSellAsset(String idempotencyKey, UUID accountId, String targetAssetCode, AssetType targetAssetType,
                                String paymentCurrency, Money sellQuantity, BigDecimal sellUnitPrice, OffsetDateTime transactedAt, String ledgerMonth,
                                BigDecimal exchangeRate, boolean isStaleRate, BigDecimal fiatToBaseRate) {

        IdempotencyOutcome idempotency = registerIdempotencyKey(
                accountId, "SELL", idempotencyKey, "이미 처리 중인 매도 요청입니다.");
        if (idempotency.replayedTradeId() != null) {
            return idempotency.replayedTradeId();
        }

        // 계좌 상태를 트랜잭션 안에서 다시 확정 검증한다.
        // Facade 의 사전 검사만 믿으면 검사와 실제 거래 사이에 계좌가 정지되어도 거래가 완료된다(TOCTOU).
        requireActiveAccount(accountId);

        // 기장 월은 Facade 가 확정한 값을 기본으로 하되, 트랜잭션 안에서 최신 월을 재확인한다.
        // Facade 의 확정과 이 트랜잭션의 커밋 사이에 다른 거래가 다음 달 원장을 만들었으면(월 경계 경합)
        // 이 거래를 이전 달에 기장하는 순간 조회 경로(MAX(ledger_month))에서 사라진다.
        String effectiveMonth = resolveEffectiveMonth(accountId, ledgerMonth);
        MonthlyAccountLedger targetAssetLedger = monthlyLedgerResolver
                .resolveOrInitializeLedger(accountId, targetAssetCode, targetAssetType, effectiveMonth);
        MonthlyAccountLedger fiatLedger = monthlyLedgerResolver
                .resolveOrInitializeLedger(accountId, paymentCurrency, AssetType.FIAT, effectiveMonth);

        // 매도로 획득한 법정 화폐 수익금 = 매도 수량 * 매도 단가.
        // 고객이 수취하는 금액이므로 DOWN 으로 정규화해 지급액이 부풀려지지 않게 한다.
        BigDecimal earnedFiatRaw = sellQuantity.getAmount().multiply(sellUnitPrice);
        requireAboveMinimumNotional(earnedFiatRaw, paymentCurrency, "매도 대금");
        Money earnedFiatAmount = Money.of(earnedFiatRaw, AssetType.FIAT, paymentCurrency, RoundingMode.DOWN);

        // 매도 자산 잔고 차감 및 당시 평균 단가 계산
        BigDecimal averageCost = targetAssetLedger.subtractBalance(sellQuantity);

        // 수익금을 법정 화폐 원장에 반영. 법정화폐 원장의 평균 단가는 기준 통화 환산 단가를 의미한다.
        BigDecimal appliedFiatToBaseRate = fiatToBaseRate != null ? fiatToBaseRate : BigDecimal.ONE;
        fiatLedger.addBalance(earnedFiatAmount, appliedFiatToBaseRate);

        monthlyAccountLedgerRepository.save(fiatLedger);
        monthlyAccountLedgerRepository.save(targetAssetLedger);

        UUID tradeId = UUID.randomUUID();

        // 잔고 반영 후 거래 성공 이벤트 생성 및 발행
        TradeExecutedEvent event = new TradeExecutedEvent(
            tradeId, accountId, targetAssetCode, targetAssetType, paymentCurrency,
            targetAssetLedger.getBaseCurrency(),
            TradeType.SELL,
            sellQuantity.getAmount(), sellUnitPrice, exchangeRate, appliedFiatToBaseRate, averageCost,
            isStaleRate, transactedAt
        );

        eventPublisher.publishEvent(event);

        // 완료된 거래 ID 를 멱등성 레코드에 기록한다(같은 트랜잭션이므로 원자적).
        // 이 기록이 있어야 타임아웃 후 재전송된 동일 요청이 409 대신 기존 거래 ID 를 돌려받는다.
        idempotency.record().complete(tradeId);

        log.info("Monthly Ledger updated for SELL. TradeID: {}", tradeId);
        return tradeId;
    }

    private void requireActiveAccount(UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        if (!account.isActive()) {
            throw new InvalidAccountStateException("Account is not active for trading: " + accountId);
        }
    }

    /**
     * 멱등성 키 등록 결과.
     *
     * @param record          새로 등록된 레코드. 재생(replay)인 경우 null
     * @param replayedTradeId 같은 키로 이미 완료된 거래의 ID. 신규 등록인 경우 null
     */
    private record IdempotencyOutcome(IdempotencyRecord record, UUID replayedTradeId) {}

    /**
     * 멱등성 키를 등록합니다. 키는 (계좌, 연산 종류) 로 스코프됩니다.
     *
     * <p>클라이언트가 보낸 키를 전역 네임스페이스로 쓰면 (1) 다른 사용자가 이미 쓴 키와 충돌해
     * 정상 거래가 거부되고, (2) 임의 키 선점으로 타인의 거래를 막는 공격이 가능해집니다.
     *
     * <p>이미 등록된 키가 <b>완료된 거래</b>를 가리키면 그 거래 ID 를 재생(replay)으로 반환해,
     * 타임아웃 후 재시도하는 클라이언트가 성공 응답을 되찾을 수 있게 합니다. 아직 처리 중인
     * 키(거래 ID 미기록)만 중복 요청 예외로 거부합니다.
     */
    private IdempotencyOutcome registerIdempotencyKey(UUID accountId, String operation,
                                                      String idempotencyKey, String duplicateMessage) {
        String scopedKey = accountId + ":" + operation + ":" + idempotencyKey;
        
        // 1. 먼저 조회하여 재시도인지 확인 (정상 완료된 거래 재생)
        IdempotencyRecord existing = idempotencyRepository.findById(scopedKey).orElse(null);
        if (existing != null) {
            if (existing.getTradeId() != null) {
                log.info("멱등 재전송 감지. 기존 거래 ID 를 반환합니다. key={}, tradeId={}", scopedKey, existing.getTradeId());
                return new IdempotencyOutcome(null, existing.getTradeId());
            }
            // 이미 처리 중(tradeId = null)인 경우
            throw new DuplicateTradeRequestException(duplicateMessage);
        }

        // 2. 없으면 새로 삽입 시도
        try {
            return new IdempotencyOutcome(
                    idempotencyRepository.saveAndFlush(new IdempotencyRecord(scopedKey)), null);
        } catch (DataIntegrityViolationException e) {
            // 동시성 경합으로 동시에 삽입을 시도한 경우 (Race condition)
            // 트랜잭션은 어차피 Abort 되지만, 여기서 추가 쿼리를 하지 않고 예외를 던짐
            throw new DuplicateTradeRequestException(duplicateMessage);
        }
    }

    /**
     * 트랜잭션 안에서 실효 원장 월을 최종 확정합니다.
     *
     * <p>Facade 가 확정한 월과 이 트랜잭션 사이에 다른 거래가 다음 달 원장을 만들어 둔 경우
     * (월 경계 경합) 최신 월로 재귀속합니다. 이월(Carry-forward)이 원본 행의 버전을 강제
     * 증가시키므로({@code PESSIMISTIC_FORCE_INCREMENT}), 이전 달 행을 이미 읽어 둔 거래는
     * 커밋 시점에 낙관적 락 충돌로 재시도되고, 재시도가 이 메서드를 다시 통과하면서 새 달로
     * 안전하게 옮겨집니다. 이 재확인이 없으면 재시도가 같은 이전 달에 기장해 보고 잔고에서
     * 조용히 사라집니다.
     */
    private String resolveEffectiveMonth(UUID accountId, String requestedMonth) {
        String latestExisting = monthlyAccountLedgerRepository
                .findLatestLedgerMonthByAccountId(accountId)
                .orElse(requestedMonth);
        if (latestExisting.compareTo(requestedMonth) > 0) {
            log.warn("요청된 기장 월({})보다 최신 원장 월({})이 이미 존재합니다. "
                    + "월 경계 경합으로 판단하여 최신 월에 기장합니다. accountId={}",
                    requestedMonth, latestExisting, accountId);
            return latestExisting;
        }
        return requestedMonth;
    }

    /**
     * 거래 대금이 결제 통화의 최소 단위 이상인지 검증합니다.
     *
     * 이 검증이 없으면 통화 스케일 정규화가 금액을 소멸시키거나 부풀립니다. KRW 처럼 스케일이 0 인
     * 통화에서 0.4 원짜리 거래는 지불 0 원(무상 취득) 또는 수취 1 원(통화 증식)이 됩니다.
     */
    private void requireAboveMinimumNotional(BigDecimal rawAmount, String currencyCode, String label) {
        BigDecimal minimumUnit = CurrencyScaleResolver.minimumUnit(AssetType.FIAT, currencyCode);
        if (rawAmount.abs().compareTo(minimumUnit) < 0) {
            throw new BelowMinimumNotionalException(String.format(
                    "%s(%s %s)이 %s 최소 단위(%s)보다 작아 거래를 처리할 수 없습니다.",
                    label, rawAmount.toPlainString(), currencyCode, currencyCode, minimumUnit.toPlainString()));
        }
    }
}
