package com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.acl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.github.raonjena99.multi_currency_ledger_service.transaction.domain.LedgerDeadLetter;
import com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.LedgerDeadLetterRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
@DisplayName("단위 테스트: 원장 DLT 컨슈머")
class LedgerDltConsumerTest {

    @Mock private LedgerDeadLetterRepository deadLetterRepository;

    @Test
    @DisplayName("DLT 메시지를 로그로 흘려보내지 않고 DB에 격리하고 지표를 올린다")
    void persists_dead_letter_and_increments_metric() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LedgerDltConsumer consumer = new LedgerDltConsumer(deadLetterRepository, registry);

        consumer.consumeDlt("{\"tradeId\":\"x\"}", "boom", "LedgerRecordingCommand", "corr-1");

        ArgumentCaptor<LedgerDeadLetter> captor = ArgumentCaptor.forClass(LedgerDeadLetter.class);
        verify(deadLetterRepository).save(captor.capture());

        LedgerDeadLetter saved = captor.getValue();
        assertThat(saved.getOriginalTopic()).isEqualTo("LedgerRecordingCommand");
        assertThat(saved.getErrorMessage()).isEqualTo("boom");
        assertThat(saved.getPayload()).contains("tradeId");
        assertThat(saved.getCorrelationId()).isEqualTo("corr-1");
        assertThat(saved.isResolved()).isFalse();

        assertThat(registry.get("ledger.dead_letter.count").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("헤더가 없는 메시지도 예외 없이 격리한다")
    void tolerates_missing_headers() {
        LedgerDltConsumer consumer = new LedgerDltConsumer(deadLetterRepository, new SimpleMeterRegistry());

        // 헤더를 필수로 선언하면 헤더 없는 메시지에서 변환이 실패해 DLT 컨슈머가 무한 재시도에 빠진다.
        consumer.consumeDlt("payload", null, null, null);

        verify(deadLetterRepository).save(any(LedgerDeadLetter.class));
    }

    @Test
    @DisplayName("이미 해결된 격리 건은 다시 해결 처리할 수 없다")
    void cannot_resolve_twice() {
        LedgerDeadLetter deadLetter = LedgerDeadLetter.isolate("topic", "err", "payload", null);
        deadLetter.markAsResolved();

        assertThat(deadLetter.isResolved()).isTrue();
        assertThat(deadLetter.getResolvedAt()).isNotNull();
        org.assertj.core.api.Assertions.assertThatThrownBy(deadLetter::markAsResolved)
                .isInstanceOf(IllegalStateException.class);
    }
}
