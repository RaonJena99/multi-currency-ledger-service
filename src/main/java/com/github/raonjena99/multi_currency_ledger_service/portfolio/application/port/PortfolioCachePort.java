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
     * 포트폴리오 정보를 캐시에 저장합니다.
     * 
     * @param accountId 계좌 ID
     * @param dto 캐시할 포트폴리오 정보
     */
    void savePortfolioCache(UUID accountId, PortfolioCacheDto dto);

    /**
     * 캐시된 포트폴리오 정보를 삭제합니다.
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
