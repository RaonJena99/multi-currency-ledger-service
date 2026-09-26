package com.github.raonjena99.multi_currency_ledger_service.portfolio.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;
import com.github.raonjena99.multi_currency_ledger_service.account.domain.event.TradeExecutedEvent;
import com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType;
import com.github.raonjena99.multi_currency_ledger_service.common.model.TradeType;
import com.github.raonjena99.multi_currency_ledger_service.portfolio.application.port.PortfolioCachePort;

/**
 * 거래 커밋 직후 포트폴리오 캐시가 "커밋한 스레드에서" 지워지는지 검증한다.
 *
 * <p>비동기 갱신에만 맡기면 커밋과 갱신 사이에 프로세스가 죽을 때 갱신 작업이 사라져, 거래 이전 잔고가
 * 캐시 TTL 동안 서빙된다. 클라이언트가 성공 응답을 받기 전에 캐시가 비어 있어야 한다.
 */
@DisplayName("포트폴리오 캐시: 커밋 직후 동기 삭제")
class PortfolioCacheEvictionOnCommitTest extends IntegrationTestSupport {

    @MockitoSpyBean private PortfolioCachePort portfolioCachePort;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("거래 이벤트가 커밋되면 트랜잭션을 끝낸 스레드가 반환하기 전에 캐시를 삭제한다")
    void evictsCacheOnCommittingThreadBeforeReturning() {
        UUID accountId = UUID.randomUUID();
        AtomicReference<String> firstEvictThread = new AtomicReference<>();
        doAnswer(invocation -> {
            firstEvictThread.compareAndSet(null, Thread.currentThread().getName());
            return invocation.callRealMethod();
        }).when(portfolioCachePort).evictPortfolioCache(accountId);

        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(new TradeExecutedEvent(
                UUID.randomUUID(), accountId, "BTC", AssetType.CRYPTO, "KRW", "KRW", TradeType.BUY,
                BigDecimal.ONE, new BigDecimal("10000"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO,
                false, OffsetDateTime.now())));

        // 트랜잭션 템플릿이 반환된 시점에 이미 이 스레드에서 삭제가 끝나 있어야 한다.
        assertThat(firstEvictThread.get())
                .as("캐시 삭제가 비동기 스레드로 미뤄지면 커밋 직후 프로세스가 죽을 때 삭제가 유실된다")
                .isEqualTo(Thread.currentThread().getName());
    }
}
