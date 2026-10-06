package com.github.raonjena99.multi_currency_ledger_service.portfolio.application.port;

import java.util.Optional;
import java.util.UUID;

import com.github.raonjena99.multi_currency_ledger_service.portfolio.application.dto.PortfolioCacheDto;

/**
 * 포트폴리오 데이터를 캐싱하기 위한 아웃바운드 포트(인터페이스)입니다.
 * 인프라스트럭처 계층(Redis 등)과 애플리케이션 계층을 분리하는 역할을 합니다.
 */
public interface PortfolioCachePort {
    
    /**
     * 계좌 ID로 캐시된 포트폴리오 정보를 조회합니다.
     * 
     * @param accountId 계좌 ID
     * @return 캐시된 포트폴리오 DTO (존재할 경우)
     */
    Optional<PortfolioCacheDto> getPortfolioCache(UUID accountId);

    /**
     * 계좌의 현재 캐시 세대를 반환합니다. 커밋 후 캐시를 지울 때마다 1 씩 오릅니다.
     *
     * <p>캐시를 다시 채우는 쪽은 <b>DB 를 읽기 전에</b> 세대를 받아 두고, 쓸 때
     * {@link #savePortfolioCacheIfGeneration} 에 넘겨야 합니다.
     *
     * @param accountId 계좌 ID
     * @return 현재 세대. 한 번도 지운 적이 없으면 0
     */
    long currentGeneration(UUID accountId);

    /**
     * 세대가 그대로일 때만 포트폴리오 정보를 캐시에 저장합니다.
     *
     * <p>DB 를 읽은 뒤 쓰기 전에 다른 거래가 커밋되어 캐시를 지웠다면 세대가 올라가 있으므로 쓰지 않습니다.
     * 조건 없이 쓰면 지워진 자리에 거래 이전 잔고가 다시 들어갑니다.
     *
     * @param accountId          계좌 ID
     * @param dto                캐시할 포트폴리오 정보
     * @param expectedGeneration DB 를 읽기 전에 받아 둔 세대
     * @return 저장했으면 true, 그사이 세대가 바뀌어 저장하지 않았으면 false
     */
    boolean savePortfolioCacheIfGeneration(UUID accountId, PortfolioCacheDto dto, long expectedGeneration);

    /**
     * 캐시된 포트폴리오 정보를 삭제하고 세대를 1 올립니다. 두 동작은 원자적으로 수행됩니다.
     *
     * @param accountId 계좌 ID
     */
    void evictPortfolioCache(UUID accountId);

    /**
     * 분산 락을 획득하려고 시도합니다.
     * 
     * @param lockKey 락 키
     * @param timeoutSeconds 락 만료 시간 (초)
     * @return 락 획득 성공 여부
     */
    boolean tryAcquireLock(String lockKey, long timeoutSeconds);

    /**
     * 획득한 분산 락을 해제합니다.
     * 
     * @param lockKey 락 키
     */
    void releaseLock(String lockKey);
}
