package com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.batch;

import java.time.OffsetDateTime;
import java.util.Map;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.infrastructure.item.database.JpaPagingItemReader;
import org.springframework.batch.infrastructure.item.database.builder.JpaPagingItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlement;

import jakarta.persistence.EntityManagerFactory;

@Configuration
public class ReconciliationReaderConfig {

    private static final int CHUNK_SIZE = 1000;

    @Bean
    @StepScope
    public JpaPagingItemReader<ExternalSettlement> externalSettlementReader(
            EntityManagerFactory entityManagerFactory,
            @Value("#{jobParameters['startOfMonth']}") String startOfMonthStr) {
        
        OffsetDateTime startOfMonth = OffsetDateTime.parse(startOfMonthStr);
        OffsetDateTime endOfMonth = startOfMonth.plusMonths(1);

        return new JpaPagingItemReaderBuilder<ExternalSettlement>()
                .name("externalSettlementReader")
                .entityManagerFactory(entityManagerFactory)
                .pageSize(CHUNK_SIZE)
                .queryString("SELECT e FROM ExternalSettlement e " +
                            "WHERE e.status = 'PENDING' " +
                            "AND e.settlementDate >= :start AND e.settlementDate < :end " +
                            "ORDER BY e.settlementDate ASC, e.id ASC")
                .parameterValues(Map.of("start", startOfMonth, "end", endOfMonth))
                .build();
    }
}
