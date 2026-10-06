package com.github.raonjena99.multi_currency_ledger_service.common.config;

import java.util.Map;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.raonjena99.multi_currency_ledger_service.common.exception.ErrorResponse;
import com.github.raonjena99.multi_currency_ledger_service.common.security.HeaderPrincipalResolver;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * OpenAPI(Swagger) 문서 설정입니다.
 *
 * <p>문서와 Swagger UI 는 관리 포트(기본 9091)의 {@code /actuator/openapi}, {@code /actuator/swagger-ui} 에서만
 * 제공합니다({@code springdoc.use-management-port}). 앱 포트에 열면 API 전체 구조와 관리자 경로 목록이 외부에
 * 드러나므로, 운영에서 외부에 노출하지 않는 관리 포트에 둡니다.
 *
 * <p>이 서비스는 토큰을 직접 검증하지 않고 게이트웨이가 넣어 준 헤더를 믿습니다. 그래서 인증을 헤더 4개의
 * 보안 스키마로 표현합니다. Swagger UI 의 Authorize 에서 필요한 헤더만 채우면 됩니다(관리자 API 는 주체와 역할,
 * 계좌 API 는 주체와 계좌 ID).
 */
@Configuration
public class OpenApiConfig {

    private static final String ERROR_SCHEMA = "ErrorResponse";

    /** 모든 /api 경로에 공통으로 붙는 실패 응답. 개별 응답은 컨트롤러의 {@code @ApiResponse} 로 문서화한다. */
    private static final Map<String, String> COMMON_ERRORS = Map.of(
            "400", "입력값 오류 (INVALID_INPUT)",
            "401", "인증 정보 없음. 게이트웨이 헤더가 없거나 시크릿이 틀림",
            "403", "권한 없음. 남의 계좌이거나 관리자 전용 API",
            "500", "서버 내부 오류");

    @Bean
    public OpenAPI ledgerOpenApi() {
        Components components = new Components()
                .addSecuritySchemes("subject", header(HeaderPrincipalResolver.SUBJECT_HEADER,
                        "주체 식별자 (필수). 상태 변경 이력의 처리자로도 남는다"))
                .addSecuritySchemes("accountId", header(HeaderPrincipalResolver.ACCOUNT_HEADER,
                        "주체가 소유한 계좌 UUID. 계좌 API 의 소유권 검사에 쓰인다"))
                .addSecuritySchemes("roles", header(HeaderPrincipalResolver.ROLES_HEADER,
                        "쉼표로 구분한 역할. ADMIN 또는 ROLE_ADMIN 이면 관리자"))
                .addSecuritySchemes("gatewaySecret", header(HeaderPrincipalResolver.GATEWAY_SECRET_HEADER,
                        "ledger.security.gateway-secret 을 설정한 환경에서 필수"));

        return new OpenAPI()
                .info(new Info()
                        .title("Multi-Currency Ledger Service API")
                        .version("v1")
                        .description("법정화폐·암호화폐를 함께 다루는 복식부기 원장 서비스. "
                                + "인증은 API 게이트웨이가 검증 후 넣어 준 X-Auth-* 헤더로 합니다."))
                .components(components)
                .addSecurityItem(new SecurityRequirement()
                        .addList("subject").addList("accountId").addList("roles").addList("gatewaySecret"));
    }

    /**
     * 공통 실패 응답을 붙이고, 모든 실패 응답의 본문을 에러 응답 형식({@code ErrorResponse})으로 맞춥니다.
     */
    @Bean
    public OpenApiCustomizer errorResponseCustomizer() {
        return openApi -> {
            Map<String, Schema> schemas = ModelConverters.getInstance().read(ErrorResponse.class);
            schemas.forEach(openApi.getComponents()::addSchemas);

            openApi.getPaths().values().forEach(pathItem -> pathItem.readOperations().forEach(operation -> {
                ApiResponses responses = operation.getResponses();
                COMMON_ERRORS.forEach((code, description) -> {
                    if (!responses.containsKey(code)) {
                        responses.addApiResponse(code, new ApiResponse().description(description));
                    }
                });
                // 실패 응답 본문은 항상 GlobalExceptionHandler 의 ErrorResponse 다. 내용 없이 선언한 @ApiResponse 에는
                // springdoc 이 메서드의 성공 반환 타입을 채워 넣으므로, 4xx·5xx 는 비어 있지 않아도 덮어쓴다.
                responses.forEach((code, response) -> {
                    if (code.charAt(0) >= '4') {
                        response.setContent(new Content().addMediaType("application/json",
                                new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + ERROR_SCHEMA))));
                    }
                });
            }));
        };
    }

    private static SecurityScheme header(String name, String description) {
        return new SecurityScheme().type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER)
                .name(name).description(description);
    }
}
