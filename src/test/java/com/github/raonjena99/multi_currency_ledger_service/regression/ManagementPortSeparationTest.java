package com.github.raonjena99.multi_currency_ledger_service.regression;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;

/**
 * 관리용 엔드포인트(actuator)가 앱 포트가 아니라 별도 관리 포트에서만 응답하는지 검증합니다.
 *
 * <p>앱 포트에서 {@code /actuator/prometheus} 가 응답하면, 앱 포트에 닿는 누구나 플랫폼 전체 보유액
 * 같은 사업 지표를 읽을 수 있습니다. 게이트웨이 시크릿은 {@code X-Auth-*} 헤더에만 적용되므로
 * 이 경로를 막지 못합니다.
 *
 * <p>실제 웹 서버로 띄웁니다. MockMvc 는 포트 구분이 없어 이 계약을 검증할 수 없습니다.
 * {@code MANAGEMENT_PORT=0} 은 설정 파일이 관리 포트를 이 변수에서 읽을 때만 효과가 있으므로,
 * 설정에서 관리 포트 분리를 지우면 이 테스트가 실패합니다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "MANAGEMENT_PORT=0")
// 이 클래스만 쓰는 컨텍스트(실제 웹 서버)다. 캐시에 남겨 두면 커넥션 풀이 테스트 DB 의 연결 한도를 채워
// 뒤에 뜨는 다른 컨텍스트가 "too many clients" 로 기동하지 못한다.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("회귀 테스트: 관리 포트 분리")
class ManagementPortSeparationTest extends IntegrationTestSupport {

    @LocalServerPort private int appPort;
    @LocalManagementPort private int managementPort;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> get(int port, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("앱 포트에서는 지표 엔드포인트가 응답하지 않는다")
    void appPortDoesNotServeMetrics() throws Exception {
        assertThat(get(appPort, "/actuator/prometheus").statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("앱 포트에서는 헬스 엔드포인트도 응답하지 않는다")
    void appPortDoesNotServeHealth() throws Exception {
        assertThat(get(appPort, "/actuator/health").statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("관리 포트에서는 지표를 수집할 수 있다")
    void managementPortServesMetrics() throws Exception {
        HttpResponse<String> response = get(managementPort, "/actuator/prometheus");

        assertThat(managementPort).isNotEqualTo(appPort);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("ledger_integrity_mismatches");
    }

    @Test
    @DisplayName("관리 포트에서는 헬스 확인이 된다")
    void managementPortServesHealth() throws Exception {
        HttpResponse<String> response = get(managementPort, "/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }
}
