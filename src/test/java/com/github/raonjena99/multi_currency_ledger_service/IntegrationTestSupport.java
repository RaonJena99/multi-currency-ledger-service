package com.github.raonjena99.multi_currency_ledger_service;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.AuditorAware;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import jakarta.persistence.EntityManagerFactory;

import java.util.Optional;
import java.util.TimeZone;

@SpringBootTest
@ActiveProfiles("test")
@Import(IntegrationTestSupport.TestJpaAuditingConfig.class)
@SuppressWarnings("resource")
public abstract class IntegrationTestSupport {

    static final PostgreSQLContainer<?> POSTGRES_CONTAINER;
    static final GenericContainer<?> REDIS_CONTAINER;

    static {
        POSTGRES_CONTAINER = new PostgreSQLContainer<>("postgres:15-alpine")
                .withDatabaseName("ledger_test_db")
                .withUsername("test_admin")
                .withPassword("test_password")
                .withEnv("TZ", "UTC");
        
        REDIS_CONTAINER = new GenericContainer<>("redis:7-alpine")
                .withExposedPorts(6379);

        POSTGRES_CONTAINER.start();
        REDIS_CONTAINER.start();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES_CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES_CONTAINER::getUsername);
        registry.add("spring.datasource.password", POSTGRES_CONTAINER::getPassword);
        
        registry.add("spring.data.redis.host", REDIS_CONTAINER::getHost);
        registry.add("spring.data.redis.port", REDIS_CONTAINER::getFirstMappedPort);
    }

    @BeforeAll
    static void initJvmTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @TestConfiguration
    public static class TestJpaAuditingConfig {
        @Bean
        public AuditorAware<String> auditorProvider() {
            return () -> Optional.of("SYSTEM_TEST");
        }

        @Bean
        @Primary
        public PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
            return new JpaTransactionManager(entityManagerFactory);
        }
    }
}