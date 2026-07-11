package com.github.raonjena99.multi_currency_ledger_service.common.config;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import com.github.raonjena99.multi_currency_ledger_service.common.telemetry.KafkaCorrelationInterceptor;

/**
 * Kafka Producer(카프카 프로듀서) 관련 설정을 담당하는 KafkaProducerConfig 클래스입니다.
 */
@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    /**
     * 기본 ProducerFactory(프로듀서 팩토리)를 생성하여 빈으로 등록합니다.
     * 문자열 기반의 키와 값을 사용하며, 멱등성(idempotence)을 활성화합니다.
     *
     * @return 설정이 적용된 ProducerFactory 인스턴스
     */
    @Bean
    @Primary
    public ProducerFactory<String, String> primaryProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

        props.put(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG, KafkaCorrelationInterceptor.class.getName());
        
        return new DefaultKafkaProducerFactory<>(props);
    }

    /**
     * 기본 KafkaTemplate(카프카 템플릿)을 생성하여 빈으로 등록합니다.
     * 이 템플릿을 사용하여 Kafka(카프카) 토픽에 메시지를 전송할 수 있습니다.
     *
     * @return 메시지 전송을 위한 KafkaTemplate 인스턴스
     */
    @Bean
    @Primary
    public KafkaTemplate<String, String> primaryKafkaTemplate() {
        return new KafkaTemplate<>(primaryProducerFactory());
    }
}