package com.github.raonjena99.multi_currency_ledger_service.common.config;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * JPA 감사(Auditing) 및 리포지토리 관련 설정을 담당하는 JpaConfig 클래스입니다.
 */
@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "offsetDateTimeProvider")
@EnableJpaRepositories(basePackages = "com.github.raonjena99.multi_currency_ledger_service")
public class JpaConfig { 

    /**
     * JPA Entity(엔티티)의 생성/수정 시간을 기록할 때 사용할 DateTimeProvider를 제공합니다.
     * 항상 현재 시간을 OffsetDateTime 기준으로 반환합니다.
     *
     * @return 현재 시간을 담고 있는 Optional<OffsetDateTime>
     */
    @Bean
    public DateTimeProvider offsetDateTimeProvider() {
        return () -> Optional.of(OffsetDateTime.now());
    }
}