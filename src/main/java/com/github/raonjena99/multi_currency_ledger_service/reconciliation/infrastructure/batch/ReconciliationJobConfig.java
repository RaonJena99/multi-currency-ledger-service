package com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.batch;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;

import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.batch.HeuristicMatchingProcessor;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.batch.MatchedReconciliationResult;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.batch.ReconciliationResultWriter;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.exception.UnmatchableSettlementException;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlement;

import lombok.RequiredArgsConstructor;

// 패키지 및 임포트 유지
@Configuration
@RequiredArgsConstructor
public class ReconciliationJobConfig {

    private static final int CHUNK_SIZE = 1000;

    private final JobRepository jobRepository;
    
    private final PlatformTransactionManager transactionManager;

    private final ItemReader<ExternalSettlement> externalSettlementReader;
    private final HeuristicMatchingProcessor heuristicMatchingProcessor;
    private final ReconciliationResultWriter reconciliationResultWriter; 
    private final ReconciliationSkipListener skipListener;

    @Bean
    public Job monthlyReconciliationJob() {
        return new JobBuilder("monthlyReconciliationJob", jobRepository)
                .start(reconciliationStep())
                .build();
    }

    @Bean
    public Step reconciliationStep() {
        DefaultTransactionAttribute attribute = new DefaultTransactionAttribute();
        attribute.setTimeout(300);

        return new StepBuilder("reconciliationStep", jobRepository)
                .<ExternalSettlement, MatchedReconciliationResult>chunk(CHUNK_SIZE)
                .transactionManager(transactionManager)
                .reader(externalSettlementReader)
                .processor(heuristicMatchingProcessor)
                .writer(reconciliationResultWriter)
                .faultTolerant()
                .skip(UnmatchableSettlementException.class)
                .skipLimit(50000)
                .listener(skipListener)
                .transactionAttribute(attribute)
                .build();
    }
}