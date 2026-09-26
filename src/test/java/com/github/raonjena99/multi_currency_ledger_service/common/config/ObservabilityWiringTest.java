package com.github.raonjena99.multi_currency_ledger_service.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.github.raonjena99.multi_currency_ledger_service.IntegrationTestSupport;

import io.micrometer.core.aop.TimedAspect;

/**
 * Boot 4 는 management.observations.annotations.enabled 가 꺼져 있으면 TimedAspect 를 등록하지 않는다.
 * 그러면 외부 API 어댑터의 @Timed 가 조용히 무시되어 응답 시간 지표가 하나도 만들어지지 않는다.
 */
@DisplayName("배선 검증: @Timed 지표 수집")
class ObservabilityWiringTest extends IntegrationTestSupport {

    @Autowired(required = false) private TimedAspect timedAspect;

    @Test
    @DisplayName("TimedAspect 가 빈으로 등록되어 @Timed 메서드의 실행 시간이 기록된다")
    void timedAspectIsRegistered() {
        assertThat(timedAspect)
                .as("TimedAspect 가 없으면 external.api.* 응답 시간 지표가 만들어지지 않는다")
                .isNotNull();
    }
}
