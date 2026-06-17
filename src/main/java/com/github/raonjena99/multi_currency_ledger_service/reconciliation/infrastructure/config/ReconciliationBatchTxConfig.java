package com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.config;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
public class ReconciliationBatchTxConfig {

    /**
     * 대규모 대사 배치(Spring Batch) 전용 트랜잭션 매니저
     */
    @Bean(name = "batchTransactionManager")
    public PlatformTransactionManager batchTransactionManager(DataSource dataSource) {
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        
        transactionManager.setDefaultTimeout(300);
        
        log.info("[INFO] Initialized 'batchTransactionManager' for large-scale reconciliation batch [timeout=300s]");
        return transactionManager;
    }
}
