package com.github.raonjena99.multi_currency_ledger_service;

import java.util.Optional;
import java.util.TimeZone;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.AuditorAware;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import jakarta.persistence.EntityManagerFactory;

@SpringBootTest
@ActiveProfiles("test")
@SuppressWarnings("resource")
public abstract class IntegrationTestSupport {

    protected static final PostgreSQLContainer<?> POSTGRES_CONTAINER;
    protected static final GenericContainer<?> REDIS_CONTAINER;
    protected static final KafkaContainer KAFKA_CONTAINER; 

    static {
        POSTGRES_CONTAINER = new PostgreSQLContainer<>("postgres:15-alpine")
                .withDatabaseName("ledger_test_db")
                .withUsername("test_admin")
                .withPassword("test_password")
                .withEnv("TZ", "UTC");
        
        REDIS_CONTAINER = new GenericContainer<>("redis:7-alpine")
                .withExposedPorts(6379);

        KAFKA_CONTAINER = new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.8.0"));

        POSTGRES_CONTAINER.start();
        REDIS_CONTAINER.start();
        KAFKA_CONTAINER.start();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES_CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES_CONTAINER::getUsername);
        registry.add("spring.datasource.password", POSTGRES_CONTAINER::getPassword);
        
        registry.add("spring.data.redis.host", REDIS_CONTAINER::getHost);
        registry.add("spring.data.redis.port", REDIS_CONTAINER::getFirstMappedPort);
        
        registry.add("spring.kafka.bootstrap-servers", KAFKA_CONTAINER::getBootstrapServers);
    }

    @BeforeAll
    static void initJvmTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.jdbc.core.JdbcTemplate systemAccountJdbcTemplate;

    /**
     * 매 테스트 종료 후 시스템 계정을 복구합니다.
     *
     * <p>{@code transaction_entries.account_id} 에는 {@code accounts(id)} 외래키가 걸려 있고,
     * 반올림 잔차 플러그({@code SYSTEM_FX_GAIN/LOSS})와 수수료 분개는 시스템 계정을 참조합니다.
     * 테스트가 {@code TRUNCATE TABLE ... accounts} 를 수행하면 마이그레이션이 시딩한 시스템 계정이
     * 사라져, <b>이후 테스트</b>의 플러그 분개가 외래키 위반으로 실패합니다.
     *
     * <p>각 테스트가 직접 복구하도록 맡기면 새 테스트를 추가할 때마다 잊어버립니다. JUnit 5 는
     * 하위 클래스의 {@code @AfterEach} 를 먼저 실행하므로, 여기에 두면 어떤 테스트가 어떻게
     * 정리하든 그 뒤에 항상 복구됩니다.
     */
    /**
     * 테스트가 생성한 계좌만 지우고 시스템 계정은 보존합니다.
     *
     * <p>{@code TRUNCATE TABLE accounts} 로 싹 지운 뒤 테스트 코드가 시스템 계정을 다시 넣는 방식은
     * <b>쓰면 안 됩니다.</b> 그러면 마이그레이션이 시딩을 빠뜨려도 테스트 헬퍼가 그 부재를 메워
     * 결함이 영원히 드러나지 않습니다. (실제로 시딩 마이그레이션을 지우는 뮤테이션이 검출되지
     * 않는 것을 확인했습니다.)
     *
     * <p>대신 스키마가 소유한 데이터는 건드리지 않고, 테스트가 만든 행만 제거합니다.
     * 자식 테이블을 먼저 비운 뒤 호출하십시오.
     */
    protected void deleteTestAccounts() {
        if (systemAccountJdbcTemplate == null) {
            return;
        }
        systemAccountJdbcTemplate.update(
                "DELETE FROM accounts WHERE id NOT IN (?::uuid, ?::uuid)",
                SYSTEM_FX_ACCOUNT_ID, SYSTEM_FEE_ACCOUNT_ID);
    }

    /** 반올림 잔차 플러그가 귀속되는 시스템 계정. 마이그레이션이 시딩합니다. */
    protected static final String SYSTEM_FX_ACCOUNT_ID = "00000000-0000-0000-0000-000000000000";

    /** 수수료 분개가 귀속되는 시스템 계정. 마이그레이션이 시딩합니다. */
    protected static final String SYSTEM_FEE_ACCOUNT_ID = "00000000-0000-0000-0000-000000000001";

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