package com.github.raonjena99.multi_currency_ledger_service.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;

class OutboxPipelineIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private OutboxRelayWorker outboxRelayWorker;

    @Test
    @DisplayName("아웃박스 이벤트가 발행되면 워커가 이를 읽어 Kafka로 전송하고 완료 처리한다")
    void shouldRelayOutboxEventToKafkaAndMarkAsProcessed() {
        // 1. Given
        OutboxEvent event = new OutboxEvent("Account", "ACC-1234", "ACCOUNT_CREATED", "{\"status\":\"ACTIVE\"}");
        outboxRepository.save(event);

        // 2. When
        outboxRelayWorker.relayOutboxEvents();

        // 3. Then (DB 검증)
        await().atMost(15, TimeUnit.SECONDS).until(() -> {
            OutboxEvent processedEvent = outboxRepository.findById(event.getId()).orElseThrow();
            return processedEvent.isProcessed();
        });

        // 4. Then (Kafka 검증)
        Map<String, Object> consumerProps = new HashMap<>();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_CONTAINER.getBootstrapServers());
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "test-group-" + System.currentTimeMillis());
        consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        var consumerFactory = new DefaultKafkaConsumerFactory<String, String>(consumerProps);
        var consumer = consumerFactory.createConsumer();
        consumer.subscribe(java.util.Collections.singletonList("ACCOUNT_CREATED"));

        ConsumerRecord<String, String> record = KafkaTestUtils.getSingleRecord(consumer, "ACCOUNT_CREATED", Duration.ofSeconds(5));
        
        assertThat(record.value()).isEqualTo("{\"status\":\"ACTIVE\"}");
        consumer.close();
    }
}