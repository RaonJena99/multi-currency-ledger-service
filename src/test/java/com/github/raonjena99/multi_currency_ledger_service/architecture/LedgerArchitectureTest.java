package com.github.raonjena99.multi_currency_ledger_service.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

import com.github.raonjena99.multi_currency_ledger_service.MultiCurrencyLedgerServiceApplication;

@DisplayName("아키텍처 자동 검증 및 문서화 테스트")
class LedgerArchitectureTest {

    // 모듈(Bounded Context) 식별
    ApplicationModules modules = ApplicationModules.of(MultiCurrencyLedgerServiceApplication.class);

    @Test
    @DisplayName("도메인 모듈 간의 순환 참조나 잘못된 의존성이 없는지 검증한다.")
    void verifyModularStructure() {
        // 검증 (부패 방지)
        modules.verify();
    }

    @Test
    @DisplayName("현재 코드 구조를 바탕으로 최신 C4 아키텍처 다이어그램을 자동 생성한다.")
    void generateLivingDocumentation() {
        // 빌드 폴더에 PlantUML 및 Structurizr 포맷으로 다이어그램 파일을 자동 생성
        new Documenter(modules)
            .writeModulesAsPlantUml() // 전체 컴포넌트 다이어그램
            .writeIndividualModulesAsPlantUml() // 각 모듈별 상세 다이어그램
            .writeModuleCanvases(); // DDD Bounded Context 캔버스 자동 생성
    }
}

