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
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.exception.UnmatchableSettlementException;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.domain.ExternalSettlement;

import org.springframework.web.client.RestClientException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;

import lombok.RequiredArgsConstructor;

// 패키지 및 임포트 유지
/**
 * 월간 대사 배치 작업(ReconciliationJob)을 정의하는 Spring Batch 설정 클래스입니다.
 */
@Configuration
@RequiredArgsConstructor
public class ReconciliationJobConfig {

    private static final int CHUNK_SIZE = 1000;

    /** 매칭 실패(비즈니스) 허용 건수. 이 이상이면 룰 엔진이나 데이터에 문제가 있다고 본다. */
    private static final int BUSINESS_SKIP_LIMIT = 50_000;

    /** 통신 장애 허용 건수. 넘으면 배치를 실패로 끝내 장애가 드러나게 한다. */
    private static final int INFRASTRUCTURE_SKIP_LIMIT = 100;

    private final JobRepository jobRepository;
    
    private final PlatformTransactionManager transactionManager;

    private final ItemReader<ExternalSettlement> externalSettlementReader;
    private final HeuristicMatchingProcessor heuristicMatchingProcessor;
    private final ReconciliationResultWriter reconciliationResultWriter; 

    private final ReconciliationSkipListener skipListener;
    private final PgApiSkipListener pgApiSkipListener;

    /**
     * 대사 배치 작업(Job)을 생성하고 반환합니다.
     * 
     * @return 월간 대사 처리 잡 (Job)
     */
    @Bean
    public Job monthlyReconciliationJob() {
        return new JobBuilder("monthlyReconciliationJob", jobRepository)
                .start(reconciliationStep())
                .build();
    }

    /**
     * 대사 작업의 핵심 단계를 구성합니다. (읽기 -> 처리 -> 쓰기)
     * 예외 발생 시의 스킵(Skip) 정책과 트랜잭션 타임아웃 등을 설정합니다.
     * 
     * @return 대사 처리 스텝 (Step)
     */
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
                // 커스텀 스킵 정책이 야기하던 더블 카운팅 방지를 위해 프레임워크의 무상태 스킵 정책을 사용합니다.
                .skip(UnmatchableSettlementException.class)
                .skip(RestClientException.class)
                .skip(CallNotPermittedException.class)
                .skipLimit(BUSINESS_SKIP_LIMIT)
                .listener(skipListener)
                .listener(pgApiSkipListener)
                .listener((Object) heuristicMatchingProcessor)
                .transactionAttribute(attribute)
                .build();
    }
}