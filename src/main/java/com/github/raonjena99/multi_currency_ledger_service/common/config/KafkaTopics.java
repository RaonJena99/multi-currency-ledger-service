package com.github.raonjena99.multi_currency_ledger_service.common.config;

/**
 * 애플리케이션이 쓰는 Kafka 토픽 이름입니다.
 *
 * <p>발행(아웃박스 이벤트의 eventType), 구독(@KafkaListener), 선언(NewTopic)이 모두 이 상수를 참조합니다.
 * 문자열을 곳곳에 따로 적으면 한 곳만 바뀌어도 메시지가 아무도 구독하지 않는 토픽으로 조용히 흘러갑니다.
 */
public final class KafkaTopics {

    /** 원장 기록 커맨드. 거래·입출금·수수료 보정이 아웃박스를 거쳐 발행한다. 키는 계좌 ID 다. */
    public static final String LEDGER_RECORDING = "LedgerRecordingCommand";

    /** 원장 기록에 끝내 실패한 메시지. LedgerDltConsumer 가 ledger_dead_letters 에 격리한다. */
    public static final String LEDGER_RECORDING_DLT = LEDGER_RECORDING + ".DLT";

    private KafkaTopics() {
    }
}
