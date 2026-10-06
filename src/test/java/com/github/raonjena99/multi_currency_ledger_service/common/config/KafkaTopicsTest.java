package com.github.raonjena99.multi_currency_ledger_service.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.MessageListenerContainer;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;

/**
 * 원장 커맨드 토픽을 애플리케이션이 직접 선언하는지 검증합니다.
 *
 * <p>테스트 브로커는 토픽 자동 생성을 꺼 둡니다(IntegrationTestSupport). 운영 클러스터에서 흔한 설정이며,
 * 이 상태에서 토픽을 선언하지 않으면 첫 발행이 실패합니다.
 */
@DisplayName("Kafka 토픽 선언")
class KafkaTopicsTest extends IntegrationTestSupport {

    @Autowired private KafkaListenerEndpointRegistry listenerRegistry;

    private Map<String, TopicDescription> describe(List<String> topics) throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_CONTAINER.getBootstrapServers()))) {
            return admin.describeTopics(topics).allTopicNames().get();
        }
    }

    private Config topicConfig(String topic) throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_CONTAINER.getBootstrapServers()))) {
            ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
            return admin.describeConfigs(List.of(resource)).all().get().get(resource);
        }
    }

    @Test
    @DisplayName("원장 커맨드 토픽과 DLT 토픽이 설정한 파티션 수로 만들어진다")
    void declaresLedgerTopicsWithConfiguredPartitions() throws Exception {
        Map<String, TopicDescription> topics = describe(List.of(
                KafkaTopics.LEDGER_RECORDING, KafkaTopics.LEDGER_RECORDING_DLT));

        assertThat(topics.get(KafkaTopics.LEDGER_RECORDING).partitions()).hasSize(3);
        assertThat(topics.get(KafkaTopics.LEDGER_RECORDING_DLT).partitions()).hasSize(3);
    }

    @Test
    @DisplayName("DLT 토픽은 보존 기간(7일)을 두고, 원장 커맨드 토픽은 브로커 기본값을 따른다")
    void dltTopicHasRetention() throws Exception {
        String sevenDaysMs = String.valueOf(7L * 24 * 60 * 60 * 1000);

        assertThat(topicConfig(KafkaTopics.LEDGER_RECORDING_DLT).get(TopicConfig.RETENTION_MS_CONFIG).value())
                .isEqualTo(sevenDaysMs);
    }

    @Test
    @DisplayName("원장 기록 컨슈머 동시성은 파티션 수에 맞춘다")
    void ledgerConsumerConcurrencyMatchesPartitions() {
        MessageListenerContainer container = listenerRegistry.getAllListenerContainers().stream()
                .filter(c -> List.of(c.getContainerProperties().getTopics()).contains(KafkaTopics.LEDGER_RECORDING))
                .findFirst().orElseThrow();

        assertThat(((ConcurrentMessageListenerContainer<?, ?>) container).getConcurrency()).isEqualTo(3);
    }
}
