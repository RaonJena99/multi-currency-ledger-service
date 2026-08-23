package com.github.raonjena99.multi_currency_ledger_service.common.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class KafkaCorrelationInterceptorTest {

    private KafkaCorrelationInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new KafkaCorrelationInterceptor();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void onSend_should_add_correlation_id_to_headers() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "test-corr-id");
        
        ProducerRecord<String, String> record = new ProducerRecord<>("topic", "key", "value");
        ProducerRecord<String, String> modifiedRecord = interceptor.onSend(record);
        
        byte[] headerValue = modifiedRecord.headers().lastHeader(CorrelationIdFilter.MDC_KEY).value();
        assertThat(new String(headerValue)).isEqualTo("test-corr-id");
    }

    @Test
    void onSend_should_not_add_header_if_correlation_id_missing() {
        MDC.remove(CorrelationIdFilter.MDC_KEY);
        
        ProducerRecord<String, String> record = new ProducerRecord<>("topic", "key", "value");
        ProducerRecord<String, String> modifiedRecord = interceptor.onSend(record);
        
        assertThat(modifiedRecord.headers().lastHeader(CorrelationIdFilter.MDC_KEY)).isNull();
    }
    
    @Test
    void empty_methods_should_not_throw() {
        // Just calling them for coverage
        interceptor.onAcknowledgement(null, null);
        interceptor.close();
        interceptor.configure(Collections.emptyMap());
        // No assertions needed, just ensuring no exceptions are thrown
    }
}
