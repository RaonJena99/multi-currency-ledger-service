package com.github.raonjena99.multi_currency_ledger_service.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;

/**
 * Kafka 컨슈머 인프라가 실제로 컨텍스트에 올라오는지 검증합니다.
 *
 * spring-kafka 만 의존성에 선언하면 Spring Boot 4 의 Kafka 자동 설정 모듈이 클래스패스에서 빠져
 * ConsumerFactory / KafkaListenerContainerFactory 가 만들어지지 않고, @KafkaListener 가 조용히
 * 전부 무력화됩니다. 프로듀서는 수동 설정으로 동작하기 때문에 이 결함은 발행 테스트만으로는
 * 절대 드러나지 않습니다. 그래서 배선 자체를 검증합니다.
 */
@DisplayName("배선 검증: Kafka 컨슈머 인프라")
class KafkaWiringTest extends IntegrationTestSupport {

    @Autowired(required = false) private ConsumerFactory<?, ?> consumerFactory;
    @Autowired(required = false) private KafkaListenerEndpointRegistry registry;

    @Test
    @DisplayName("ConsumerFactory 와 리스너 레지스트리가 빈으로 등록된다")
    void consumer_infrastructure_is_present() {
        assertThat(consumerFactory)
                .as("ConsumerFactory 가 없으면 @KafkaListener 가 전부 무력화된다")
                .isNotNull();
        assertThat(registry)
                .as("KafkaListenerEndpointRegistry 가 없으면 리스너 컨테이너가 생성되지 않는다")
                .isNotNull();
    }

    @Test
    @DisplayName("원장 커맨드 토픽과 DLT 를 구독하는 리스너 컨테이너가 실제로 실행 중이다")
    void ledger_listeners_are_running() {
        assertThat(registry).isNotNull();

        var topics = registry.getListenerContainers().stream()
                .flatMap(c -> {
                    String[] t = c.getContainerProperties().getTopics();
                    return t == null ? java.util.stream.Stream.<String>empty() : java.util.Arrays.stream(t);
                })
                .toList();

        assertThat(topics).contains("LedgerRecordingCommand", "LedgerRecordingCommand.DLT");
        assertThat(registry.getListenerContainers())
                .isNotEmpty()
                .allSatisfy(c -> assertThat(c.isRunning()).as("리스너 컨테이너가 실행 중이어야 한다").isTrue());
    }
}
