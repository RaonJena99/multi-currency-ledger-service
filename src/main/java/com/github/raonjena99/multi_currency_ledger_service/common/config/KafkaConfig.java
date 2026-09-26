package com.github.raonjena99.multi_currency_ledger_service.common.config;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka 컨슈머 오류 처리 정책을 정의하는 설정 클래스입니다.
 */
@Configuration
public class KafkaConfig {

    /** DB 일시 장애 재시도의 최대 대기 간격. 커넥션 대기(30초)와 합쳐도 max.poll.interval(5분)보다 짧아야 한다. */
    private static final long DB_OUTAGE_MAX_INTERVAL_MS = 30_000L;

    @Bean
    public DefaultErrorHandler errorHandler(KafkaOperations<?, ?> kafkaOperations) {

        // DLT(Dead Letter Topic)로 메시지를 보내는 Recoverer 생성.
        //
        // 목적지 결정에 주의: 이 에러 핸들러는 DLT 컨슈머의 리스너 컨테이너에도 적용된다.
        // 기본 규칙(topic + ".DLT")을 그대로 쓰면 DLT 컨슈머가 실패했을 때 아무도 구독하지 않는
        // "X.DLT.DLT" 토픽으로 발행되어, "잔고는 바뀌었는데 분개가 없는" 치명적 기록이 블랙홀로
        // 사라진다. 이미 DLT 인 토픽의 실패는 같은 DLT 토픽 뒤로 재적재해, 원인(대개 DB 장애)이
        // 복구될 때까지 유실 없이 순환하게 한다.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaOperations,
                (consumerRecord, exception) -> {
                    String destination = consumerRecord.topic().endsWith(".DLT")
                            ? consumerRecord.topic()
                            : consumerRecord.topic() + ".DLT";
                    // 파티션 -1: 프로듀서가 파티션을 결정하게 위임한다.
                    return new org.apache.kafka.common.TopicPartition(destination, -1);
                });

        // 백오프 정책 설정: 1초 대기 후 최대 3번 재시도
        FixedBackOff backOff = new FixedBackOff(1000L, 3L);
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);

        // DB 일시 장애는 DLT 로 보내지 않고 DB 가 돌아올 때까지 재시도한다.
        //
        // 기본 백오프(4회)는 커넥션 대기 30초와 합쳐 약 2분이면 끝난다. DB 가 그보다 오래 내려가면
        // 이미 잔고가 바뀐 거래의 원장 커맨드가 DLT 로 빠지는데, DLT 는 ledger_dead_letters 에
        // 적재만 할 뿐 재처리 경로가 없어 분개가 영구히 누락된다. 분개 기록은 거래 ID 로 멱등하므로
        // 몇 번을 재처리해도 안전하다. 파티션이 막히는 동안 뒤따르는 메시지도 어차피 DB 가 필요하다.
        ExponentialBackOff untilDbRecovers = new ExponentialBackOff(1000L, 2.0);
        untilDbRecovers.setMaxInterval(DB_OUTAGE_MAX_INTERVAL_MS);
        errorHandler.setBackOffFunction((consumerRecord, exception) ->
                isTransientDbFailure(exception) ? untilDbRecovers : null);

        // 재시도해도 결과가 달라지지 않는 예외는 재시도 없이 즉시 DLT로 직행시킨다.
        //
        // 이 애플리케이션의 JSON 처리는 Jackson 3(tools.jackson)을 사용하므로 역직렬화 실패는
        // tools.jackson.core.JacksonException 으로 올라온다. Jackson 2 의
        // com.fasterxml.jackson.core.JsonProcessingException 을 등록하면 클래스패스에 두 버전이
        // 공존하는 탓에 컴파일은 되지만 분류가 절대 매칭되지 않아 재시도만 낭비된다.
        errorHandler.addNotRetryableExceptions(
                tools.jackson.core.JacksonException.class,
                IllegalArgumentException.class);

        return errorHandler;
    }

    /**
     * DB 에 닿지 못해 실패한 것인지 판별합니다. 리스너 예외는 여러 겹으로 감싸여 오므로 원인 사슬을 모두 확인합니다.
     *
     * <p>낙관적 락 충돌({@code TransientDataAccessException} 계열)도 포함됩니다. 충돌은 다시 시도하면
     * 해소되므로, 경합이 이어진다는 이유만으로 DLT 로 보내 수동 처리 대상으로 만들 이유가 없습니다.
     */
    static boolean isTransientDbFailure(Throwable exception) {
        Throwable current = exception;
        for (int depth = 0; current != null && depth < 20; depth++) {
            if (current instanceof CannotCreateTransactionException
                    || current instanceof TransientDataAccessException
                    || current instanceof DataAccessResourceFailureException
                    || current instanceof SQLTransientConnectionException) {
                return true;
            }
            // SQLSTATE 08xxx: 연결 예외(connection exception) 클래스
            if (current instanceof SQLException sql && sql.getSQLState() != null
                    && sql.getSQLState().startsWith("08")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
