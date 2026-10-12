package com.github.raonjena99.multi_currency_ledger_service.account.application;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import com.github.raonjena99.multi_currency_ledger_service.account.domain.FxQuote;

/**
 * 환전 견적 저장소 포트입니다. 견적은 몇십 초만 유효하므로 만료가 있는 저장소(Redis)에 둡니다.
 *
 * <p>저장소가 만료된 견적과 처음부터 없던 견적을 구분하지 못합니다. 호출하는 쪽은 둘을 같은 "견적이 없거나 만료됨"으로
 * 다룹니다. 견적의 단일 사용은 저장소가 아니라 체결 기록의 유니크 제약이 보장합니다.
 */
public interface FxQuoteStore {

    /**
     * 견적을 저장합니다. 유효 시간이 지나면 저장소가 알아서 지웁니다.
     *
     * @param quote 저장할 견적
     * @param ttl   유효 시간
     * @throws com.github.raonjena99.multi_currency_ledger_service.common.exception.FxQuoteUnavailableException
     *         저장소를 쓸 수 없는 경우
     */
    void save(FxQuote quote, Duration ttl);

    /**
     * 견적을 찾습니다.
     *
     * @param quoteId 견적 ID
     * @return 없거나 만료되었으면 비어 있다
     * @throws com.github.raonjena99.multi_currency_ledger_service.common.exception.FxQuoteUnavailableException
     *         저장소를 쓸 수 없는 경우
     */
    Optional<FxQuote> find(UUID quoteId);
}
