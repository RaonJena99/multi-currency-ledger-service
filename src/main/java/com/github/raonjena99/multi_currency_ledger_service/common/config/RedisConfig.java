package com.github.raonjena99.multi_currency_ledger_service.common.config;

import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import io.lettuce.core.ClientOptions;

@Configuration
public class RedisConfig {
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        return template;
    }

    /**
     * Redis 연결이 끊긴 동안에는 명령을 대기시키지 않고 즉시 실패시킵니다.
     *
     * <p>Lettuce 기본값은 재연결을 기다리며 명령을 쌓아 두다가 커맨드 타임아웃(2초)이 지나야 실패시킵니다.
     * 캐시·락 호출에는 모두 폴백이 있지만, 그 폴백이 호출마다 2초 뒤에야 실행됩니다. 시세 조회는
     * 서킷 브레이커가 감싼 메서드 안에서 Redis 를 여러 번 부르므로, Redis 가 재시작되는 동안 공급자 호출이
     * "느린 호출"로 집계되어 서킷이 열리고, 폴백도 캐시를 읽지 못해 모든 거래가 503 이 됩니다.
     * 즉시 실패시키면 캐시 미스로 바로 넘어가 보조 의존성인 Redis 의 장애가 거래 장애로 번지지 않습니다.
     *
     * <p>Boot 가 만든 옵션(연결 타임아웃, 커맨드 타임아웃 등)은 그대로 두고 이 항목만 바꿉니다.
     */
    @Bean
    public LettuceClientOptionsBuilderCustomizer rejectCommandsWhileDisconnected() {
        return builder -> builder.disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
    }
}
