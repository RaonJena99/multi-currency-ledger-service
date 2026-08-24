package com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.batch;

import java.time.OffsetDateTime;
import java.util.Map;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.infrastructure.item.database.JpaCursorItemReader;
import org.springframework.batch.infrastructure.item.database.builder.JpaCursorItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.raonjena99.multi_currency_ledger_service.common.model.SettlementStatus;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlement;

import jakarta.persistence.EntityManagerFactory;

/**
 * 대사 배치의 ItemReader를 정의하는 설정 클래스입니다.
 * 데이터베이스로부터 대기 중(PENDING)인 ExternalSettlement(외부 정산) 엔티티를 페이징하여 읽어옵니다.
 */
@Configuration
public class ReconciliationReaderConfig {
    /**
     * JPA 페이징 기반으로 외부 정산 데이터를 읽어오는 리더(ItemReader)를 생성합니다.
     * 
     * @param entityManagerFactory JPA 엔티티 매니저 팩토리
     * @param startOfMonthStr Job 파라미터로 전달된 대상 월의 시작일 (문자열)
     * @return JPA 페이징 아이템 리더 (JpaPagingItemReader)
     */
    @Bean
    @StepScope
    public org.springframework.batch.infrastructure.item.database.JpaPagingItemReader<ExternalSettlement> externalSettlementReader(
        EntityManagerFactory entityManagerFactory,
        @Value("#{jobParameters['startOfMonth']}") String startOfMonthStr) {
        
        // Job Parameter로 받은 시작일(startOfMonthStr)을 바탕으로 조회 범위(해당 월의 1일 ~ 말일)를 계산합니다.
        OffsetDateTime startOfMonth = OffsetDateTime.parse(startOfMonthStr);
        OffsetDateTime endOfMonth = startOfMonth.plusMonths(1);

        
        return new org.springframework.batch.infrastructure.item.database.builder.JpaPagingItemReaderBuilder<ExternalSettlement>()
            .name("externalSettlementReader")
            .entityManagerFactory(entityManagerFactory)
            // 정렬은 반드시 결정적이어야 한다. settlementDate 만으로 정렬하면 같은 시각을 가진
            // 행들의 순서가 페이지마다 달라져 LIMIT/OFFSET 페이징이 행을 건너뛰거나 중복 처리한다.
            // id 를 2차 정렬 키로 넣어 전순서(total order)를 보장한다.
            .queryString("SELECT e FROM ExternalSettlement e " +
                        "WHERE e.settlementDate >= :start AND e.settlementDate < :end " +
                        "ORDER BY e.settlementDate ASC, e.id ASC")
            .parameterValues(Map.of(
                    "start", startOfMonth, 
                    "end", endOfMonth))
            .pageSize(1000)
            .build();
        }
}