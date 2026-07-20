package com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.acl;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 처리에 완전히 실패한 원장 기록 메시지(Dead Letter)를 수집하는 컨슈머입니다.
 */
@Slf4j
@Component
public class LedgerDltConsumer {
    // 원본 토픽에 ".DLT"가 붙은 토픽을 리스닝
    @KafkaListener(topics = "LedgerRecordingCommand.DLT", groupId = "ledger-dlt-alert-group")
    public void consumeDlt(
            String payload,
            @Header(KafkaHeaders.EXCEPTION_MESSAGE) String errorMessage,
            @Header(KafkaHeaders.ORIGINAL_TOPIC) String originalTopic) {
        
        log.error("=================================================");
        log.error("🚨 [CRITICAL] 원장 기록 완전 실패 (DLT 발생) 🚨");
        log.error("Original Topic: {}", originalTopic);
        log.error("Error Cause: {}", errorMessage);
        log.error("Payload: {}", payload);
        log.error("=================================================");
        
        // [TODO] 향후 이 위치에서 다음과 같은 로직을 추가할 수 있습니다:
        // 1. Slack / 이메일 등 관리자에게 장애 알림 발송
        // 2. 수동 처리를 위해 DB 테이블(예: LedgerDeadLetter)에 저장
    }
}
