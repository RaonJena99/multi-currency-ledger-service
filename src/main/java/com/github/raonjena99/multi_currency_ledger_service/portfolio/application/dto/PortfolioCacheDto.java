package com.github.raonjena99.multi_currency_ledger_service.portfolio.application.dto;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Redis에 저장되는 실시간 포트폴리오 캐시 데이터를 위한 경량화된 DTO 클래스입니다.
 * 직렬화/역직렬화를 위해 Serializable을 구현하며, 계좌의 기준 통화(Base Currency) 및
 * 자산별 최신 잔고/매입단가 정보를 보관하여 포트폴리오 조회(CQRS) 성능을 극대화합니다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PortfolioCacheDto implements Serializable {
    private UUID accountId;
    private String baseCurrency;
    private List<AssetBalance> balances;
    
    /**
     * 포트폴리오 캐시 내 개별 자산의 잔고 및 단가 정보를 담는 내부 클래스입니다.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AssetBalance implements Serializable {
        /** 자산 코드 (예: BTC, KRW 등) */
        private String assetCode;
        
        /** 보유 총 수량 */
        private BigDecimal totalQuantity;
        
        /** 매입 평균 단가 */
        private BigDecimal avgUnitPrice;
        
        /** 매입 통화(Quote Currency) - 미실현 손익 계산 시 기준 통화로의 환율 변환에 사용됨 */
        private String quoteCurrency;
    }
}