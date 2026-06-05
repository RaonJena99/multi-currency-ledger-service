# 🏦 다중 자산 포트폴리오 불변 원장 시스템 (Multi-Asset Ledger System)

![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.0.6-brightgreen?logo=springboot&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-Latest-blue?logo=postgresql&logoColor=white)
![Gradle](https://img.shields.io/badge/Gradle-Build-02303A?logo=gradle&logoColor=white)

본 프로젝트는 도메인 주도 설계(DDD)와 복식부기 모델을 기반으로 구축된 **엔터프라이즈급 불변 원장 코어 뱅킹 플랫폼**입니다.  
과거 메모리 기반의 단일 통화 원장 시스템을 발전시켜, 완벽한 대차평균의 정합성을 보장하면서도 현대 금융 환경에 맞춘 아키텍처를 적용하고 있습니다.

---

## 🏛️ 핵심 기반 아키텍처 (Core Architecture)

핵심 뱅킹 시스템이 갖추어야 할 철저한 기본기를 충실히 반영하여 설계되었습니다.

- **불변 객체 모델링 :** 금융 시스템의 치명적인 버그인 부동소수점 오차를 원천적으로 방지하기 위한 정밀도 제어 및 불변 객체 설계를 적용했습니다.
- **견고한 동시성 제어 :** 데이터베이스의 물리적 락과 애플리케이션 레벨의 낙관적 락(Optimistic Lock)을 혼합하여 트랜잭션 동시성을 안전하게 제어합니다.
- **독립적 감사 로그 :** 트랜잭션 전파 속성을 활용해 비즈니스 핵심 로직과 감사 로그 기록의 생명주기를 분리하였습니다.
- **기능 기반 패키징 :** 응집도를 높이고 도메인 간 결합도를 낮추기 위한 기능 기반 패키지 구조를 채택했습니다.

---

## 🚀 시스템 진화 로드맵: 현재 달성 단계

본 플랫폼은 단순한 법정 화폐 입출금을 넘어 글로벌 금융 기관 수준의 다중 자산 포트폴리오를 취급하기 위해 고도화 로드맵을 밟고 있습니다.  
**현재 아키텍처는 아래의 제1단계 구축을 완료한 상태입니다.**

### 📍 [Phase 1] 다중 자산 포트폴리오 모델링과 손익 산출 아키텍처

주식, 채권, 암호화폐, 외환 등 다양한 자산 클래스를 하나의 원장에서 수용할 수 있도록 원장 스키마와 손익 산출 파이프라인을 근본적으로 재설계하였습니다.

#### 1. 다중 자산 복식부기 스키마 설계 (Multi-Asset Double-Entry Schema)

이종 통화 간의 환율, 수량, 단가가 복합적으로 작용하는 환경에 대응하기 위해 전통적인 복식부기 모델을 확장했습니다.

- **가변적 정밀도 제어:** 암호화폐(최대 소수점 18자리) 등 다양한 자산의 특성에 맞게 고정 스케일이 아닌 가변적 정밀도 제어를 지원합니다.
- **풍부한 트랜잭션 컨텍스트:** 원장 테이블은 자산 코드 식별자뿐만 아니라, 거래 수량, 거래 당시 평가액을 나타내는 단가 및 환율 필드를 포함합니다.
- **이종 자산 간 복식부기 정합성:** 주식 매수 트랜잭션 발생 시, 현금 계좌에서는 대변으로 자금을 차감함과 동시에 주식 계좌에서는 차변으로 자산 수량이 증가하는 완벽한 복식부기 분개를 생성합니다. 이는 단순 잔액 관리를 넘어 사용자 포트폴리오 구성을 투명하게 추적하는 기반이 됩니다.

#### 2. 실현 손익 및 미실현 손익 수학적 모델링 (P&L Mathematical Modeling)

사용자 보유 자산의 정확한 가치 평가를 위해, 엄격한 금융 회계 기준에 따라 손익을 실현/미실현으로 분리하여 관리하고 있습니다.  
이를 위해 원장 시스템은 단순 잔액뿐만 아니라 개별 트랜잭션 단위의 이동 평균 단가를 병행 추적합니다.

| 손익 분류                            | 산출 시점 및 조건                            | 계산 로직                                  | 시스템 아키텍처 적용 방식                                                |
| :----------------------------------- | :------------------------------------------- | :----------------------------------------- | :----------------------------------------------------------------------- |
| **미실현 손익<br/>(Unrealized P&L)** | 실시간 또는 장 마감 시점의 시장 가격 반영    | `(현재 시장 가격 × 보유 수량) - 비용 기준` | 읽기 전용 뷰(Materialized View)에서 동적으로 계산하여 인메모리 캐싱 처리 |
| **실현 손익<br/>(Realized P&L)**     | 자산의 부분 또는 전량 매도 및 결제 발생 시점 | `(매도 가격 - 평균 매입 단가) × 매도 수량` | 매도 트랜잭션 발생 시 복식부기 원장에 명시적 분개로 영구 기록            |

---

## 📂 프로젝트 구조 (Project Structure)

```text
multi-currency-ledger-service/
├── src/
│   ├── main/
│   │   ├── java/com/.../multi_currency_ledger_service/
│   │   │   ├── MultiCurrencyLedgerServiceApplication.java # 애플리케이션 진입점
│   │   │   │
│   │   │   ├── common/                                    # 공통 도메인 및 설정 (횡단 관심사)
│   │   │   │   ├── config/JpaAuditingConfig.java          # JPA Auditing (생성/수정 시간)
│   │   │   │   ├── domain/BaseEntity.java                 # 공통 엔티티
│   │   │   │   ├── exception/                             # 전역 예외 처리 및 DTO
│   │   │   │   └── model/                                 # 공통 Enum (AssetType, EntryType)
│   │   │   │
│   │   │   ├── account/                                   # [계좌 컨텍스트] 자산 잔고 관리
│   │   │   │   └── domain/AccountBalance.java             # 잔고 및 이동평균단가 관리 로직
│   │   │   │
│   │   │   └── transaction/                               # [원장 컨텍스트] 복식부기 트랜잭션
│   │   │       └── domain/
│   │   │           ├── Transaction.java                   # 트랜잭션 애그리거트 루트 (대차평균 검증)
│   │   │           └── TransactionEntry.java              # 개별 분개 항목 (가변수량/단가/환율)
│   │   │
│   │   └── resources/
│   │       ├── application.yaml                           # 다중 환경(Profile) 인프라 설정
│   │       └── db/migration/                              # Flyway 스키마 이력 관리
│   │           ├── V1__init_schema.sql                    # 초기 원장 스키마
│   │           ├── V2__add_pnl_and_average_cost.sql       # 손익/평균단가 필드 스키마
│   │           ├── V2_5__create_market_data.sql           # 시장 가격 스키마
│   │           └── V3__create_portfolio_view.sql          # 포트폴리오 다차원 조회 뷰
│   │
│   └── test/                                              # 테스트 레이어
│       └── java/com/.../
│           ├── IntegrationTestSupport.java                # 통합 테스트 인프라 (Testcontainers)
│           ├── account/domain/AccountBalanceTest.java     # 단가 계산 및 로직 검증 테스트
│           ├── repository/                                # JPA 영속성 검증 단위 테스트
│           └── transaction/domain/TransactionTest.java    # 대차평균 정합성 검증 테스트
```
