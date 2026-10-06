package com.github.raonjena99.multi_currency_ledger_service.regression;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * OpenAPI 문서가 모든 API 를 담고, 관리 포트에서만 제공되는지 검증합니다.
 *
 * <p>문서 경로를 앱 포트에 열면 API 전체 구조와 관리자 경로 목록이 외부에 드러납니다. 관리용 엔드포인트와 같이
 * 관리 포트(운영에서 외부 비노출)에서만 응답해야 합니다. 실제 웹 서버로 띄워 포트를 구분해 확인합니다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "MANAGEMENT_PORT=0")
// 이 클래스만 쓰는 컨텍스트다. 캐시에 남기면 커넥션 풀이 테스트 DB 연결 한도를 채운다(ManagementPortSeparationTest 참고).
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("회귀 테스트: OpenAPI 문서")
class ApiDocumentationTest extends IntegrationTestSupport {

    @LocalServerPort private int appPort;
    @LocalManagementPort private int managementPort;

    @Autowired @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;
    @Autowired private JsonMapper jsonMapper;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    private HttpResponse<String> get(int port, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode openApi() throws Exception {
        HttpResponse<String> response = get(managementPort, "/actuator/openapi");
        assertThat(response.statusCode()).isEqualTo(200);
        return jsonMapper.readTree(response.body());
    }

    @Test
    @DisplayName("애플리케이션에 등록된 모든 /api 경로가 문서에 있다")
    void documentsEveryApiPath() throws Exception {
        Set<String> registered = new TreeSet<>();
        handlerMapping.getHandlerMethods().keySet().forEach(info ->
                info.getPatternValues().stream().filter(p -> p.startsWith("/api/")).forEach(registered::add));

        Set<String> documented = new TreeSet<>();
        openApi().get("paths").propertyNames().forEach(documented::add);

        assertThat(registered).isNotEmpty();
        assertThat(documented).containsAll(registered);
    }

    @Test
    @DisplayName("게이트웨이 인증 헤더 4개가 보안 스키마로 정의되어 있다")
    void definesGatewayHeadersAsSecuritySchemes() throws Exception {
        JsonNode schemes = openApi().get("components").get("securitySchemes");

        Set<String> headers = new TreeSet<>();
        schemes.forEach(scheme -> {
            assertThat(scheme.get("in").asString()).isEqualTo("header");
            headers.add(scheme.get("name").asString());
        });
        assertThat(headers).containsExactlyInAnyOrder(
                "X-Auth-Subject", "X-Auth-Account-Id", "X-Auth-Roles", "X-Gateway-Secret");
    }

    @Test
    @DisplayName("주요 실패 응답이 에러 응답 형식과 함께 문서화되어 있다")
    void documentsErrorResponses() throws Exception {
        JsonNode paths = openApi().get("paths");

        JsonNode buy = paths.get("/api/v1/accounts/{accountId}/trades/buy").get("post").get("responses");
        assertThat(buy.propertyNames()).contains("200", "400", "403", "409", "422", "503");
        assertThat(buy.get("409").toString()).contains("ErrorResponse");
        // 실패 응답을 명시해도 성공 응답의 본문 형식이 함께 문서화되어야 한다.
        assertThat(buy.get("200").toString()).contains("TradeResponseDto");

        JsonNode close = paths.get("/api/v1/admin/accounts/{accountId}/close").get("post").get("responses");
        assertThat(close.propertyNames()).contains("404", "409", "422");
    }

    @Test
    @DisplayName("관리 포트에서 Swagger UI 를 볼 수 있다")
    void servesSwaggerUiOnManagementPort() throws Exception {
        HttpResponse<String> response = get(managementPort, "/actuator/swagger-ui");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).containsIgnoringCase("swagger");
    }

    @Test
    @DisplayName("앱 포트에서는 관리자로 요청해도 문서를 돌려주지 않는다")
    void appPortDoesNotServeDocs() throws Exception {
        // 인증 없이 요청하면 경로가 있든 없든 보안 필터가 401 로 막으므로 관리자로 요청한다.
        // 앱 포트는 등록되지 않은 경로를 거부(denyAll, 403)하고, actuator 경로는 매핑이 없어 404 가 된다.
        for (String path : new String[] {"/v3/api-docs", "/swagger-ui.html", "/swagger-ui/index.html", "/actuator/openapi"}) {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + appPort + path))
                            .header("X-Auth-Subject", "admin-1").header("X-Auth-Roles", "ADMIN").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(path).isIn(403, 404);
            // 404 오류 메시지에는 요청 경로가 들어가므로, 실제 OpenAPI 문서에만 있는 버전 필드로 판단한다.
            assertThat(response.body()).as(path).doesNotContain("\"openapi\":\"3");
        }
    }
}
