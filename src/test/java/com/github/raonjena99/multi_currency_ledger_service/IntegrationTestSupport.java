package com.github.raonjena99.multi_currency_ledger_service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.awaitility.Awaitility;
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
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.github.raonjena99.multi_currency_ledger_service.common.config.KafkaTopics;

import jakarta.persistence.EntityManagerFactory;

@SpringBootTest
@ActiveProfiles("test")
@SuppressWarnings("resource")
public abstract class IntegrationTestSupport {

    protected static final PostgreSQLContainer POSTGRES_CONTAINER;
    protected static final GenericContainer<?> REDIS_CONTAINER;
    protected static final KafkaContainer KAFKA_CONTAINER; 

    static {
        POSTGRES_CONTAINER = new PostgreSQLContainer("postgres:15-alpine")
                .withDatabaseName("ledger_test_db")
                .withUsername("test_admin")
                .withPassword("test_password")
                .withEnv("TZ", "UTC")
                // 테스트 컨텍스트 캐시는 설정이 다른 컨텍스트(@MockitoBean 등)를 테스트가 끝날 때까지 살려 두고,
                // 컨텍스트마다 커넥션 풀(최대 20)을 쥔다. 기본 한도(100)면 컨텍스트가 몇 개만 늘어도 뒤에 뜨는
                // 컨텍스트가 "too many clients" 로 기동하지 못한다. 풀을 줄이면 동시성 테스트가 막히므로 한도를 올린다.
                .withCommand("postgres", "-c", "max_connections=300");
        
        REDIS_CONTAINER = new GenericContainer<>("redis:7-alpine")
                .withExposedPorts(6379);

        // 토픽 자동 생성을 끈다. 운영 클러스터에서 흔한 설정이며, 애플리케이션이 쓰는 토픽을 모두 직접
        // 선언해야만(KafkaConfig 의 NewTopic) 원장 기록 테스트가 통과한다.
        KAFKA_CONTAINER = new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.8.0"))
                .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

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
        // 환전 체결 기록은 계좌를 외래키로 참조한다. 환전을 하는 테스트가 따로 지우지 않아도 계좌 정리가 막히지 않게 한다.
        systemAccountJdbcTemplate.update("DELETE FROM fx_conversions");
        systemAccountJdbcTemplate.update(
                "DELETE FROM accounts WHERE id NOT IN (?::uuid, ?::uuid, ?::uuid)",
                SYSTEM_FX_ACCOUNT_ID, SYSTEM_FEE_ACCOUNT_ID, SYSTEM_CASH_CLEARING_ACCOUNT_ID);
    }

    private static final String LEDGER_RECORDING_GROUP = "ledger-recording-group";

    /**
     * 원장 기록 파이프라인(아웃박스 → 릴레이 → Kafka → 원장 소비자)이 비기를 기다립니다.
     *
     * <p>거래·입출금은 아웃박스에 이벤트를 남기고 끝나며, 원장 기록은 그 뒤에 비동기로 일어납니다. 테스트가
     * 이를 기다리지 않고 {@code TRUNCATE} 로 정리하면, 소비자 트랜잭션과 서로의 락을 기다리다 deadlock 으로
     * 정리가 실패하거나(40P01), 지워진 계좌를 참조하는 원장 항목이 외래키 위반으로 다음 테스트에 새어 나갑니다(#140).
     * 이벤트를 일으키는 테스트는 정리 쿼리 전에 이 메서드를 부르십시오.
     *
     * <p>모든 테스트에 일괄로 걸지 않는 이유: 메트릭·보존·데드레터 테스트는 아웃박스 이벤트를 일부러 미처리 상태로
     * 두므로 기다리면 시간 초과만 납니다.
     *
     * <p>두 조건을 이 순서로 확인합니다. (1) 릴레이가 미발행 이벤트를 모두 발행했다. (2) 원장 소비자 그룹의 랙이 0 이다.
     * 오프셋은 소비자가 DB 에 기록을 끝낸 뒤(실패하면 데드레터로 보낸 뒤)에 커밋되므로 랙 0 은 처리가 끝났다는 뜻입니다.
     * 아웃박스가 비어 있으면(이벤트를 일으키지 않은 테스트) 바로 돌아옵니다.
     */
    protected void awaitLedgerPipelineIdle() {
        if (systemAccountJdbcTemplate == null || Boolean.FALSE.equals(systemAccountJdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM outbox_events)", Boolean.class))) {
            return;
        }
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_CONTAINER.getBootstrapServers()))) {
            Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(50))
                    .ignoreExceptions()
                    .untilAsserted(() -> {
                        assertThat(systemAccountJdbcTemplate.queryForObject(
                                "SELECT count(*) FROM outbox_events WHERE processed = false AND dead_letter = false",
                                Long.class)).as("릴레이가 아직 발행하지 않은 이벤트").isZero();
                        assertThat(ledgerRecordingLag(admin)).as("원장 소비자가 아직 처리하지 않은 메시지").isZero();
                    });
        }
    }

    private static long ledgerRecordingLag(AdminClient admin) throws Exception {
        String topic = KafkaTopics.LEDGER_RECORDING;
        Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(LEDGER_RECORDING_GROUP)
                .partitionsToOffsetAndMetadata().get();
        Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
        admin.describeTopics(List.of(topic)).allTopicNames().get().get(topic).partitions()
                .forEach(p -> latest.put(new TopicPartition(topic, p.partition()), OffsetSpec.latest()));

        long lag = 0;
        for (Map.Entry<TopicPartition, ListOffsetsResultInfo> end : admin.listOffsets(latest).all().get().entrySet()) {
            OffsetAndMetadata offset = committed.get(end.getKey());
            lag += Math.max(0, end.getValue().offset() - (offset == null ? 0 : offset.offset()));
        }
        return lag;
    }

    /** 반올림 잔차 플러그가 귀속되는 시스템 계정. 마이그레이션이 시딩합니다. */
    protected static final String SYSTEM_FX_ACCOUNT_ID = "00000000-0000-0000-0000-000000000000";

    /** 수수료 분개가 귀속되는 시스템 계정. 마이그레이션이 시딩합니다. */
    protected static final String SYSTEM_FEE_ACCOUNT_ID = "00000000-0000-0000-0000-000000000001";

    /** 입출금 분개의 상대 계정. 마이그레이션이 시딩합니다. */
    protected static final String SYSTEM_CASH_CLEARING_ACCOUNT_ID = "00000000-0000-0000-0000-000000000002";

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