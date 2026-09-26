package com.github.raonjena99.multi_currency_ledger_service.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;

import io.lettuce.core.ClientOptions;

/**
 * Redis 연결이 끊긴 동안 명령이 대기하지 않고 즉시 실패하도록 설정되었는지 검증한다.
 * 기본값(대기 후 타임아웃)이면 Redis 재시작만으로 시세 조회가 느린 호출로 집계되어 거래가 503 이 된다.
 */
@DisplayName("배선 검증: Redis 연결 끊김 시 즉시 실패")
class RedisResilienceWiringTest extends IntegrationTestSupport {

    @Autowired private LettuceConnectionFactory connectionFactory;

    @Test
    @DisplayName("Lettuce 가 연결이 끊긴 동안 명령을 거부하고, Boot 의 커맨드 타임아웃은 유지된다")
    void rejectsCommandsWhileDisconnected() {
        ClientOptions options = connectionFactory.getClientConfiguration().getClientOptions().orElseThrow();

        assertThat(options.getDisconnectedBehavior())
                .isEqualTo(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
        // 옵션 전체를 새로 만들어 덮어쓰면 Boot 가 설정한 값이 사라진다. 커스터마이저가 항목만 바꿨는지 확인한다.
        assertThat(connectionFactory.getClientConfiguration().getCommandTimeout().toMillis()).isEqualTo(2000L);
    }
}
