package com.github.raonjena99.multi_currency_ledger_service.common.infrastructure.adapter;

import java.math.BigDecimal;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * fxratesapi.com {@code /latest} 응답 매핑입니다.
 *
 * <p>실측 성공 응답:
 * <pre>
 * {"success":true,"timestamp":1787559240,"date":"2026-08-24T08:14:00.000Z",
 *  "base":"KRW","rates":{"EUR":0.000619148,"JPY":0.114993644,"USD":0.000722168}}
 * </pre>
 *
 * <p>실측 실패 응답(HTTP 400):
 * <pre>
 * {"success":false,"error":"invalid_currencies","description":"The currencies parameter is not valid."}
 * </pre>
 *
 * <p>{@code terms}, {@code privacy}, {@code date} 등 쓰지 않는 필드가 함께 오므로 미지정
 * 프로퍼티를 무시합니다. 이 프로젝트의 JSON 처리는 Jackson 3({@code tools.jackson})이지만
 * Jackson 3 는 {@code com.fasterxml.jackson.annotation} 애노테이션을 그대로 인식합니다.
 *
 * @param base        응답 기준 통화
 * @param rates       통화 코드 → 1 base 당 해당 통화 금액
 * @param success     처리 성공 여부
 * @param error       실패 시 오류 코드
 * @param description 실패 시 상세 설명
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FxRatesApiResponse(
        String base,
        Map<String, BigDecimal> rates,
        boolean success,
        String error,
        String description
) {}
