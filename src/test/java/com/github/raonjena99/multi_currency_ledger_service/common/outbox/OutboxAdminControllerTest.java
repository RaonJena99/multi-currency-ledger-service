package com.github.raonjena99.multi_currency_ledger_service.common.outbox;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.github.raonjena99.multi_currency_ledger_service.common.exception.GlobalExceptionHandler;

@DisplayName("아웃박스 관리자 API")
class OutboxAdminControllerTest {

    private final OutboxRepository outboxRepository = mock(OutboxRepository.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 실제 OutboxManager 를 사용해 "예외 발생 → 전역 처리기 매핑" 경로를 그대로 검증한다.
        OutboxAdminController controller = new OutboxAdminController(new OutboxManager(outboxRepository), outboxRepository);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("없는 이벤트 ID 를 재발행하면 500 이 아니라 404 를 돌려준다")
    void requeueUnknownEventReturnsNotFound() throws Exception {
        when(outboxRepository.findById(999L)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/admin/outbox/dead-letters/{eventId}/requeue", 999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("OUTBOX_EVENT_NOT_FOUND"));
    }
}
