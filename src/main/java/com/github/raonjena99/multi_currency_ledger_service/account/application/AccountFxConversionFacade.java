package com.github.raonjena99.multi_currency_ledger_service.account.application;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.Account;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.FxConversion;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.FxQuote;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountRepository;
import com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.FxConversionRepository;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.AccountNotFoundException;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.FxQuoteExpiredException;
import com.github.raonjena99.multi_currency_ledger_service.common.exception.InvalidAccountStateException;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 환전 체결 요청을 처리하는 Facade 입니다. 견적을 확인하고 원장 행을 준비한 뒤, 트랜잭션이 필요한 체결은
 * {@link AccountTradeService#executeFxConversion} 에 맡깁니다.
 *
 * <p>견적 저장소(Redis) 조회와 원장 행 초기화(별도 커밋)를 트랜잭션 밖에서 끝내는 것이 이 계층의 존재 이유이며, 매수·매도의
 * {@link AccountTradeFacade} 와 같은 구조입니다. 낙관적 락 재시도는 서비스 쪽에 있어 재시도가 견적 조회를 반복하지 않습니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountFxConversionFacade {

    private final FxQuoteStore quoteStore;
    private final AccountTradeService tradeService;
    private final FxConversionRepository conversionRepository;
    private final AccountRepository accountRepository;
    private final LedgerPeriodResolver periodResolver;
    private final MonthlyLedgerResolver ledgerResolver;

    /**
     * 견적을 체결합니다.
     *
     * <p>같은 멱등성 키로 이미 체결했다면 견적을 보지 않고 처음 체결을 돌려줍니다. 견적은 30초만 유효해서, 타임아웃 뒤에
     * 재전송한 요청이 이미 성공한 체결을 "견적 만료"로 응답받으면 고객이 새 견적으로 다시 환전해 이중 체결됩니다.
     *
     * <p>견적이 없거나, 만료되었거나, 다른 계좌의 것이면 모두 같은 오류입니다. 고객이 할 일이 같고, 다른 계좌의 견적이
     * 존재하는지를 알려 주지 않기 위해서입니다.
     *
     * @param accountId      체결할 계좌
     * @param quoteId        체결할 견적 ID
     * @param idempotencyKey 중복 요청 방지 키
     * @return 체결 기록
     * @throws FxQuoteExpiredException 견적이 없거나 만료되었거나 이 계좌의 견적이 아닌 경우
     */
    public FxConversion convert(UUID accountId, UUID quoteId, String idempotencyKey) {
        Optional<UUID> replayed = tradeService.findCompletedTradeId(accountId,
                AccountTradeService.FX_CONVERSION_OPERATION, idempotencyKey);
        if (replayed.isPresent()) {
            return conversionRepository.findById(replayed.get()).orElseThrow(() ->
                    new IllegalStateException("멱등성 레코드가 가리키는 환전 체결 기록이 없습니다: " + replayed.get()));
        }

        // 이후 모든 단계가 이 시각을 공유해야 월 경계에서 흔들리지 않는다.
        OffsetDateTime transactedAt = OffsetDateTime.now();

        FxQuote quote = quoteStore.find(quoteId)
                .filter(found -> found.accountId().equals(accountId))
                .filter(found -> !found.isExpiredAt(transactedAt))
                .orElseThrow(() -> new FxQuoteExpiredException(
                        "환전 견적이 없거나 만료되었습니다. 새 견적을 받아 다시 시도하세요: " + quoteId));

        // 여기서의 상태 확인은 빠른 실패(fail-fast) 목적이다. 확정 검증은 트랜잭션 안에서 다시 수행된다.
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        if (!account.isActive()) {
            throw new InvalidAccountStateException("Account is not active for FX conversion: " + accountId);
        }

        // 기장할 실효 원장 월을 계좌 단위로 한 번만 확정하고, 두 통화의 원장 행을 트랜잭션 밖에서 미리 만든다.
        // 원장 초기화는 별도 커밋이므로 모든 검증을 통과한 "뒤"에 한다(거절된 요청이 빈 원장 행을 남기지 않게).
        String ledgerMonth = periodResolver.resolveLedgerMonth(accountId, transactedAt);
        ledgerResolver.resolveOrInitializeLedger(accountId, quote.baseCurrency(), AssetType.FIAT, ledgerMonth);
        ledgerResolver.resolveOrInitializeLedger(accountId, quote.foreignCurrency(), AssetType.FIAT, ledgerMonth);

        FxConversion conversion = tradeService.executeFxConversion(idempotencyKey, quote, transactedAt, ledgerMonth);

        discardQuote(quoteId);
        return conversion;
    }

    /**
     * 체결한 견적을 저장소에서 지웁니다. 단일 사용은 체결 기록의 유니크 제약이 보장하므로, 이 정리가 실패해도 체결은 성공으로
     * 돌려줍니다. 견적은 어차피 유효 시간이 지나면 저장소가 지웁니다.
     */
    private void discardQuote(UUID quoteId) {
        try {
            quoteStore.delete(quoteId);
        } catch (RuntimeException e) {
            log.warn("체결한 환전 견적을 지우지 못했습니다. 유효 시간이 지나면 저장소가 지웁니다. quoteId={}: {}", quoteId, e.getMessage());
        }
    }
}
