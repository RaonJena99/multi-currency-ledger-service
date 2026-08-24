package com.github.raonjena99.multi_currency_ledger_service.regression;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;
import com.github.raonjena99.multi_currency_ledger_service.account.application.AccountTradeService;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.ingestion.SettlementIngestionService;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.application.ingestion.SettlementRecorder;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.ExternalSettlementRepository;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.adapter.ExternalSettlementDto;
import com.github.raonjena99.multi_currency_ledger_service.reconciliation.infrastructure.adapter.PgSettlementAdapter;

/**
 * Spring AOP 프록시에 의존하는 두 가지 계약을 고정합니다.
 *
 * <p>둘 다 <b>조용히</b> 깨지는 종류입니다. 컴파일도 되고 테스트도 통과하는데 애노테이션만 무효가 되므로,
 * 계약 자체를 검증하지 않으면 알 수 없습니다.
 * <ul>
 *   <li>같은 클래스 내부 호출(self-invocation)은 프록시를 거치지 않아 {@code @Transactional} 이 무시됩니다.</li>
 *   <li>재시도 어드바이스가 트랜잭션 어드바이스보다 안쪽에 놓이면 재시도가 무의미해집니다.</li>
 * </ul>
 */
@DisplayName("회귀 테스트: 트랜잭션 프록시 계약")
class TransactionalProxyGuardTest extends IntegrationTestSupport {

    @Autowired private AccountTradeService tradeService;
    @Autowired private SettlementIngestionService ingestionService;
    @Autowired private SettlementRecorder settlementRecorder;
    @Autowired private ExternalSettlementRepository settlementRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate txTemplate;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private PgSettlementAdapter pgSettlementAdapter;

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("TRUNCATE TABLE external_settlement CASCADE");
    }

    private void stub(String txId, String amount) {
        org.mockito.Mockito.when(pgSettlementAdapter.fetchSettlement(txId))
                .thenReturn(new ExternalSettlementDto(txId, "KRW", new BigDecimal(amount),
                        BigDecimal.ZERO, "PAID", OffsetDateTime.now()));
    }

    @Test
    @DisplayName("재시도 어드바이스가 트랜잭션 어드바이스보다 바깥에 적용된다")
    void retry_advice_wraps_transaction_advice() {
        assertThat(AopUtils.isAopProxy(tradeService)).isTrue();
        assertThat(tradeService).isInstanceOf(Advised.class);

        Integer retryOrder = null;
        Integer txOrder = null;

        for (var advisor : ((Advised) tradeService).getAdvisors()) {
            String adviceType = advisor.getAdvice().getClass().getSimpleName();
            Integer order = (advisor instanceof Ordered o) ? o.getOrder() : null;
            if (adviceType.contains("Retry")) retryOrder = order;
            if (adviceType.contains("TransactionInterceptor")) txOrder = order;
        }

        assertThat(retryOrder).as("재시도 어드바이스가 프록시에 적용되어 있어야 한다").isNotNull();
        assertThat(txOrder).as("트랜잭션 어드바이스가 프록시에 적용되어 있어야 한다").isNotNull();

        // 낮은 order 가 바깥이다. 재시도가 바깥이어야 커밋 시점 예외를 관측하고
        // 시도마다 새 트랜잭션을 시작할 수 있다.
        assertThat(retryOrder)
                .as("재시도(order=%s)가 트랜잭션(order=%s)보다 바깥이어야 한다", retryOrder, txOrder)
                .isLessThan(txOrder);
    }

    @Test
    @DisplayName("정산 단건 적재는 호출자의 트랜잭션과 분리된 독립 트랜잭션에서 커밋된다")
    void single_settlement_ingest_commits_in_its_own_transaction() {
        stub("PROXY-1", "1000");

        // 바깥 트랜잭션을 열고 그 안에서 단건 적재를 호출한 뒤, 바깥을 강제로 롤백한다.
        // REQUIRES_NEW 가 실제로 적용되었다면 적재된 정산은 롤백되지 않고 남아 있어야 한다.
        // 프록시를 우회했다면 바깥 트랜잭션에 참여해 함께 사라진다.
        txTemplate.execute(status -> {
            settlementRecorder.record("PROXY-1");
            status.setRollbackOnly();
            return null;
        });

        assertThat(settlementRepository.findByInstitutionCodeAndExternalReferenceId("PG", "PROXY-1"))
                .as("REQUIRES_NEW 가 무시되면 바깥 롤백에 함께 휩쓸려 사라진다")
                .isPresent();
    }

    @Test
    @DisplayName("목록 적재도 호출자의 트랜잭션에 휩쓸리지 않는다")
    void batch_ingest_survives_caller_rollback() {
        stub("PROXY-BATCH", "5000");

        // 이것이 self-invocation 버그를 직접 겨냥하는 테스트다.
        // ingest() 가 같은 클래스의 메서드를 직접 호출하면 프록시를 거치지 않아 REQUIRES_NEW 가
        // 무시되고, 바깥 트랜잭션에 참여해 아래 롤백에 함께 사라진다.
        txTemplate.execute(status -> {
            ingestionService.ingest(List.of("PROXY-BATCH"));
            status.setRollbackOnly();
            return null;
        });

        assertThat(settlementRepository.findByInstitutionCodeAndExternalReferenceId("PG", "PROXY-BATCH"))
                .as("건별 트랜잭션이 격리되지 않으면 바깥 롤백에 휩쓸려 사라진다")
                .isPresent();
    }

    @Test
    @DisplayName("목록 적재는 건별로 트랜잭션이 분리되어 한 건의 실패가 이미 적재된 건을 되돌리지 않는다")
    void batch_ingest_isolates_failures_per_item() {
        stub("PROXY-A", "1000");
        org.mockito.Mockito.when(pgSettlementAdapter.fetchSettlement("PROXY-FAIL"))
                .thenThrow(new RuntimeException("PG API down"));
        stub("PROXY-B", "2000");

        int ingested = ingestionService.ingest(List.of("PROXY-A", "PROXY-FAIL", "PROXY-B"));

        assertThat(ingested).isEqualTo(2);
        assertThat(settlementRepository.findByInstitutionCodeAndExternalReferenceId("PG", "PROXY-A")).isPresent();
        assertThat(settlementRepository.findByInstitutionCodeAndExternalReferenceId("PG", "PROXY-B")).isPresent();
    }

    @Test
    @DisplayName("적재 서비스는 단건 적재를 프록시 빈을 통해 호출한다")
    void ingestion_service_delegates_through_a_proxied_bean() {
        // SettlementRecorder 가 별도 빈으로 분리되어 프록시가 적용되어 있어야
        // SettlementIngestionService 의 호출이 트랜잭션 경계를 통과한다.
        assertThat(AopUtils.isAopProxy(settlementRecorder))
                .as("단건 적재 컴포넌트에 트랜잭션 프록시가 적용되어 있어야 한다")
                .isTrue();

        boolean hasRequiresNew = false;
        for (var method : SettlementRecorder.class.getDeclaredMethods()) {
            Transactional tx = method.getAnnotation(Transactional.class);
            if (tx != null && tx.propagation() == Propagation.REQUIRES_NEW) {
                hasRequiresNew = true;
            }
        }
        assertThat(hasRequiresNew).isTrue();

        // 적재 서비스 자신은 트랜잭션을 열지 않는다. 열면 건별 격리가 사라진다.
        for (var method : SettlementIngestionService.class.getDeclaredMethods()) {
            assertThat(method.getAnnotation(Transactional.class))
                    .as("목록 적재 메서드 %s 에 @Transactional 이 붙으면 건별 격리가 무의미해진다", method.getName())
                    .isNull();
        }
    }

    @Test
    @DisplayName("낙관적 락 충돌이 커밋 시점에 발생해도 재시도가 이를 관측한다")
    void retry_observes_optimistic_lock_failure_raised_at_commit() {
        // 이 계약은 별도의 통합 테스트(AccountTradeConcurrencyTest)에서 실제 경쟁 상태로도 확인되지만,
        // 여기서는 재시도 대상 예외가 커밋 단계에서 올라오는 타입인지 명시적으로 고정한다.
        UUID probe = UUID.randomUUID();
        assertThat(probe).isNotNull();

        var retryable = java.util.Arrays.stream(AccountTradeService.class.getDeclaredMethods())
                .filter(m -> m.getName().startsWith("execute"))
                .map(m -> m.getAnnotation(org.springframework.retry.annotation.Retryable.class))
                .filter(java.util.Objects::nonNull)
                .toList();

        assertThat(retryable).as("매수/매도 실행 메서드에 재시도가 선언되어 있어야 한다").hasSize(2);
        assertThat(retryable).allSatisfy(r ->
                assertThat(r.retryFor())
                        .contains(org.springframework.dao.OptimisticLockingFailureException.class));
    }
}
