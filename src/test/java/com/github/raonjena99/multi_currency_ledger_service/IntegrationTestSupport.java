package com.github.raonjena99.multi_currency_ledger_service;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.TimeZone;

@SpringBootTest
@ActiveProfiles("test")
@SuppressWarnings("resource")
public abstract class IntegrationTestSupport {

    static final PostgreSQLContainer<?> POSTGRES_CONTAINER;
    static final GenericContainer<?> REDIS_CONTAINER; // Redis 컨테이너 선언

    static {
        // Postgres 컨테이너 구성
        POSTGRES_CONTAINER = new PostgreSQLContainer<>("postgres:15-alpine")
                .withDatabaseName("ledger_test_db")
                .withUsername("test_admin")
                .withPassword("test_password")
                .withEnv("TZ", "UTC");
        
        // Redis 컨테이너 구성
        REDIS_CONTAINER = new GenericContainer<>("redis:7-alpine")
                .withExposedPorts(6379);

        // 컨테이너 동시 기동
        POSTGRES_CONTAINER.start();
        REDIS_CONTAINER.start();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        // Postgres 설정 동적 주입
        registry.add("spring.datasource.url", POSTGRES_CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES_CONTAINER::getUsername);
        registry.add("spring.datasource.password", POSTGRES_CONTAINER::getPassword);
        
        // Redis 설정 동적 주입
        registry.add("spring.data.redis.host", REDIS_CONTAINER::getHost);
        registry.add("spring.data.redis.port", REDIS_CONTAINER::getFirstMappedPort);
    }

    @BeforeAll
    static void initJvmTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }
}