package com.github.raonjena99.multi_currency_ledger_service.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.apache.kafka.common.compress.Compression;
import org.apache.kafka.common.record.CompressionType;
import org.apache.kafka.common.record.MemoryRecords;
import org.apache.kafka.common.record.MutableRecordBatch;
import org.apache.kafka.common.record.Record;
import org.apache.kafka.common.record.SimpleRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * kafka-clients 가 끌어오는 lz4-java 의 버전과 호환성을 검증합니다.
 *
 * <p>lz4-java 1.10.1 에는 1.11.4 에서 고쳐진 HIGH 취약점(CVE-2026-106451)이 있어 build.gradle 의 constraints 로
 * 올렸습니다. 이 앱은 Kafka 메시지를 압축하지 않아 다른 테스트로는 lz4 코드가 실행되지 않으므로, 올린 버전이
 * 클래스패스에 실제로 올라왔는지와 kafka-clients 의 LZ4 압축 경로가 그 버전으로 동작하는지를 여기서 직접 확인합니다.
 */
@DisplayName("lz4-java 의존성")
class Lz4DependencyTest {

    private static final int[] PATCHED_VERSION = {1, 11, 4};

    private static int[] resolvedVersion() throws IOException {
        Properties pom = new Properties();
        try (InputStream in = Lz4DependencyTest.class.getResourceAsStream(
                "/META-INF/maven/at.yawk.lz4/lz4-java/pom.properties")) {
            assertThat(in).as("lz4-java 의 pom.properties").isNotNull();
            pom.load(in);
        }
        String[] parts = pom.getProperty("version").split("\\.");
        int[] version = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            version[i] = Integer.parseInt(parts[i]);
        }
        return version;
    }

    private static boolean atLeast(int[] actual, int[] required) {
        for (int i = 0; i < required.length; i++) {
            int a = i < actual.length ? actual[i] : 0;
            if (a != required[i]) {
                return a > required[i];
            }
        }
        return true;
    }

    @Test
    @DisplayName("취약점이 고쳐진 1.11.4 이상이 클래스패스에 올라온다")
    void patchedVersionIsOnTheClasspath() throws IOException {
        int[] version = resolvedVersion();

        assertThat(atLeast(version, PATCHED_VERSION))
                .as("lz4-java %s 는 CVE-2026-106451 이 고쳐진 1.11.4 이상이어야 한다", java.util.Arrays.toString(version))
                .isTrue();
    }

    @Test
    @DisplayName("kafka-clients 의 LZ4 압축·해제가 올린 버전으로 정상 동작한다")
    void kafkaLz4RoundTripWorks() {
        // 압축이 실제로 일어나도록 반복되는 큰 값을 쓴다.
        String payload = "LedgerRecordingCommand-".repeat(500);
        MemoryRecords records = MemoryRecords.withRecords(Compression.lz4().build(),
                new SimpleRecord("account-1".getBytes(StandardCharsets.UTF_8),
                        payload.getBytes(StandardCharsets.UTF_8)));

        List<String> values = new ArrayList<>();
        for (MutableRecordBatch batch : records.batches()) {
            assertThat(batch.compressionType()).isEqualTo(CompressionType.LZ4);
            for (Record record : batch) {
                byte[] value = new byte[record.valueSize()];
                record.value().get(value);
                values.add(new String(value, StandardCharsets.UTF_8));
            }
        }

        assertThat(values).containsExactly(payload);
        assertThat(records.sizeInBytes()).as("압축되어 원본보다 작다").isLessThan(payload.length());
    }
}
