# 다중 자산 포트폴리오 불변 원장 시스템 <br> (Multi-Asset Ledger System)

![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.0.6-brightgreen?logo=springboot&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-Latest-blue?logo=postgresql&logoColor=white)
![Gradle](https://img.shields.io/badge/Gradle-Build-02303A?logo=gradle&logoColor=white)

본 프로젝트는 도메인 주도 설계(DDD)와 복식부기 모델을 기반으로 구축된 **엔터프라이즈급 불변 원장 코어 뱅킹 플랫폼**입니다.

과거 메모리 기반의 단일 통화 원장 시스템을 발전시켜, 완벽한 대차평균의 정합성을 보장하면서도 현대 금융 환경에 맞춘 아키텍처를 적용하고 있습니다.

---

## 핵심 기반 아키텍처 (Core Architecture)

핵심 뱅킹 시스템이 갖추어야 할 철저한 기본기를 충실히 반영하여 설계되었습니다.

- **불변 객체 모델링 :** 금융 시스템의 부동소수점 오차 및 이종 통화 간 연산 오류를 원천적으로 방지하기 위해 정밀도 제어 로직을 반영한 `Money` VO(Value Object) 기반의 불변 설계를 적용했습니다.
- **견고한 동시성 제어 :** 데이터베이스 제약조건과 애플리케이션 레벨의 낙관적 락(Optimistic Lock, `@Version`)을 혼합하여 트랜잭션 동시성을 안전하게 제어하고 갱신 손실(Lost Update)을 방지합니다.
- **독립적 감사 로그 및 비동기 원장 동기화 :** 트랜잭셔널 아웃박스(Transactional Outbox) 패턴을 적용하여 비즈니스 거래 처리와 원장 기록의 생명주기를 물리적으로 분리하고 시스템 간 최종 정합성(Eventual Consistency)을 보장합니다.
- **CQRS 및 기능 기반 패키징 :** 응집도를 높이고 도메인 간 결합도를 낮추기 위해 컨텍스트(Account, Transaction, Portfolio, Common) 단위의 기능 기반 패키지 구조를 채택했습니다.

---

## 시스템 진화 로드맵: 현재 달성 단계

본 플랫폼은 단순한 법정 화폐 입출금을 넘어 글로벌 금융 기관 수준의 다중 자산 포트폴리오를 취급하기 위해 고도화 로드맵을 밟고 있습니다.

**현재 아키텍처는 아래의 제3단계 구축까지 완료한 상태입니다.**

### [Phase 1] 다중 자산 포트폴리오 모델링과 손익 산출 아키텍처

주식, 채권, 암호화폐, 외환 등 다양한 자산 클래스를 하나의 원장에서 수용할 수 있도록 원장 스키마와 손익 산출 파이프라인을 근본적으로 재설계하였습니다.

#### 1. 다중 자산 복식부기 스키마 설계 (Multi-Asset Double-Entry Schema)

이종 통화 간의 환율, 수량, 단가가 복합적으로 작용하는 환경에 대응하기 위해 전통적인 복식부기 모델을 확장했습니다.

- **가변적 정밀도 제어:** 암호화폐(최대 소수점 18자리) 등 다양한 자산의 특성에 맞게 고정 스케일이 아닌 가변적 정밀도 제어를 지원합니다.
- **풍부한 트랜잭션 컨텍스트:** 원장 테이블은 자산 코드 식별자뿐만 아니라, 거래 수량, 거래 당시 평가액을 나타내는 단가 및 환율 필드를 포함합니다.
- **이종 자산 간 복식부기 정합성:** 주식 매수 트랜잭션 발생 시, 현금 계좌에서는 대변으로 자금을 차감함과 동시에 주식 계좌에서는 차변으로 자산 수량이 증가하는 복식부기 분개를 생성합니다.

#### 2. 실현 손익 및 미실현 손익 수학적 모델링 (P&L Mathematical Modeling)

사용자 보유 자산의 정확한 가치 평가를 위해, 엄격한 금융 회계 기준에 따라 손익을 실현/미실현으로 분리하여 관리하고 있습니다.

| 손익 분류                       | 산출 시점 및 조건                            | 계산 로직                                  | 시스템 아키텍처 적용 방식                                                |
| ------------------------------- | -------------------------------------------- | ------------------------------------------ | ------------------------------------------------------------------------ |
| **미실현 손익(Unrealized P&L)** | 실시간 또는 장 마감 시점의 시장 가격 반영    | `(현재 시장 가격 × 보유 수량) - 비용 기준` | 읽기 전용 뷰(Materialized View)에서 동적으로 계산하여 인메모리 캐싱 처리 |
| **실현 손익(Realized P&L)**     | 자산의 부분 또는 전량 매도 및 결제 발생 시점 | `(매도 가격 - 평균 매입 단가) × 매도 수량` | 매도 트랜잭션 발생 시 복식부기 원장에 명시적 분개로 영구 기록            |

---

### [Phase 2] 비동기 이벤트 기반 원장 동기화 및 코어 트레이딩 아키텍처

컨텍스트 간의 물리적 결합도를 낮추고 대규모 트래픽 환경하에서도 데이터 정합성을 보장하기 위해 분산 메시징 기초 인프라와 타입 안전성을 강화했습니다.

#### 1. Transactional Outbox 패턴 기반의 비동기 원장 동기화

- **이벤트 원자성 보장:** 계좌 도메인의 자산 변동 비즈니스 로직과 변동 이벤트(`TradeExecutedEvent`) 적재를 단일 로컬 트랜잭션으로 묶어 아웃박스 테이블에 영속화합니다.
- **최종 정합성 달성:** 주기적으로 작동하는 `OutboxRelayWorker`가 미처리 이벤트를 검출 및 폴링하여 원장 서비스(`LedgerService`)의 복식부기 분개 적재 로직을 트리거함으로써 시스템 간 정합성을 비동기적으로 완성합니다.

#### 2. 부패 방지 계층(ACL, Anti-Corruption Layer) 도입을 통한 도메인 격리

- 수신된 도메인 이벤트를 분석하여 하류 원장 내부 규격에 최적화된 `LedgerRecordingCommand`로 명시적 번역을 수행하여 모듈 간 전염을 방지합니다.

---

### [Phase 3] 월차 원장 패턴(Monthly Ledger) 및 CQRS 기반 포트폴리오 읽기 최적화

무한히 누적되는 단일 원장의 구조적 한계(스냅샷 역설)를 극복하고, 대규모 트래픽 환경에서 실시간 포트폴리오 조회 성능을 극대화하기 위해 **쓰기(Write)와 읽기(Read) 아키텍처를 완전히 분리**했습니다.

#### 1. 시간 파티셔닝 기반 월차 원장 패턴 (Monthly Ledger Pattern)

- **생명주기 단절 및 자동 이월(Rollover):** 기존 단일 장부(`AccountBalance`)를 폐기하고, 월 단위(YYYY-MM)로 생명주기가 단절되는 `MonthlyAccountLedger`를 도입했습니다. 월이 변경되면 이전 달의 잔액과 평단가를 바탕으로 당월 장부를 자동 이월 생성합니다.
- **고도화된 트랜잭션 락 제어:** `MonthlyLedgerResolver`를 도입하여, 장부 최초 생성(INSERT) 시에만 `REQUIRES_NEW` 트랜잭션을 열어 다중 스레드 환경의 유니크 제약조건 충돌을 방어합니다. 비즈니스 로직에는 순수 영속화된(Managed) 객체만 반환하여 완벽한 더티 체킹(Dirty Checking)과 낙관적 락을 보장합니다.

#### 2. CQRS 및 구체화된 뷰(Materialized View)를 통한 조회 격리

- **조회 전용 바운디드 컨텍스트 (`Portfolio` 모듈):** 원장 기록 책임과 자산 평가(조회) 책임을 물리적으로 분리했습니다.
- **Lock-Free 비동기 투영(Projection):** 트랜잭션이 성공적으로 커밋된 직후(`AFTER_COMMIT`), 백그라운드 스레드(`@Async`)에서 `PortfolioViewRefresher`가 RDBMS의 `CONCURRENTLY` 옵션을 사용하여 뷰를 갱신합니다. 쓰기와 읽기의 DB 락(Lock) 경합이 원천 차단됩니다.
- **O(1) 포트폴리오 API 서빙:** 복잡한 실시간 환율 및 손익 계산(조인 연산)은 구체화된 뷰에서 처리하고, `PortfolioQueryService`와 `PortfolioController`는 DTO 스냅샷만 즉시 읽어 클라이언트에게 O(1) 수준의 속도로 응답합니다.

---

## 📊 System Architecture (Living Documentation)

> 본 아키텍처 다이어그램은 코드가 변경됨에 따라 CI/CD 파이프라인에 의해 자동으로 최신화됩니다.

### 1. 시스템 전체 컴포넌트

![System Components](docs/architecture/components.svg)

### 2. Bounded Context

| Account (계좌 모듈)                                     | Transaction (원장 모듈)                                         |
| :------------------------------------------------------ | :-------------------------------------------------------------- |
| ![Account Module](docs/architecture/module-account.svg) | ![Transaction Module](docs/architecture/module-transaction.svg) |

---

## 프로젝트 구조 (Project Structure)

```text
multi-currency-ledger-service/
├── src/
│   ├── main/
│   │   ├── java/com/.../multi_currency_ledger_service/
│   │   │   ├── MultiCurrencyLedgerServiceApplication.java # 애플리케이션 진입점 (@EnableAsync, @EnableScheduling 추가)
│   │   │   │
│   │   │   ├── common/                                    # 공통 도메인 및 인프라 (횡단 관심사)
│   │   │   │   ├── domain/Money.java                      # [핵심] 금액·수량 계산 및 자산타입 스케일 제어 VO
│   │   │   │   ├── outbox/                                # Transactional Outbox 엔티티, 저장소 및 워커
│   │   │   │   └── exception/GlobalExceptionHandler.java  # 낙관적 락 충돌 및 도메인 규칙 전역 예외 처리기
│   │   │   │
│   │   │   ├── account/                                   # [Write Model] 월차 원장 및 코어 매매 비즈니스
│   │   │   │   ├── application/AccountTradeService.java   # 자산 매수/매도 트랜잭션 처리 (순수 더티 체킹 활용)
│   │   │   │   ├── application/MonthlyLedgerResolver.java # 장부 생성/이월 및 동시성 락 충돌 우회 제어기
│   │   │   │   ├── domain/MonthlyAccountLedger.java       # 시간 단위(월차)로 분할된 원장 엔티티 (AccountBalance 대체)
│   │   │   │   └── domain/event/TradeExecutedEvent.java   # 주문 체결 완료 알림 도메인 이벤트
│   │   │   │
│   │   │   ├── portfolio/                                 # [Read Model] CQRS 기반 포트폴리오 집계 및 조회 (신설)
│   │   │   │   ├── presentation/PortfolioController.java  # O(1) 성능의 클라이언트 서빙 REST API
│   │   │   │   ├── application/PortfolioQueryService.java # 구체화된 뷰 조회 및 총자산/손익(DTO) 스냅샷 집계
│   │   │   │   ├── application/PortfolioViewRefresher.java# 트랜잭션 커밋 직후 백그라운드 뷰 갱신 리스너 (@Async)
│   │   │   │   └── domain/CurrentPortfolio.java           # DB Materialized View와 매핑되는 Immutable 조회 엔티티
│   │   │   │
│   │   │   └── transaction/                               # [복식부기 컨텍스트] 대차평균 원장 관리
│   │   │       ├── application/LedgerService.java         # 복식부기 자동 분개 처리 및 멱등성 보장 서비스
│   │   │       ├── domain/Transaction.java                # 트랜잭션 애그리거트 루트 (대차평균 정합성 사전 검증)
│   │   │       └── infrastructure/acl/OrderToLedgerAcl.java # 상류 이벤트를 가로채 원장 커맨드로 번역하는 방어 계층
│   │   │
│   │   └── resources/
│   │       └── db/migration/                              # Flyway 스키마 마이그레이션 이력
│   │           ├── V1 ~ V6...                             # 초기화, 아웃박스, 다통화 정규화 스키마
│   │           ├── V7__create_monthly_ledger.sql          # 월차 원장 테이블(monthly_account_ledgers) 생성 및 복합 유니크 제약
│   │           └── V8__create_portfolio_view.sql          # 실시간 손익 계산이 포함된 CQRS 구체화된 뷰 스크립트 추가
│   │
│   └── test/                                              # 엔터프라이즈 환경 테스트 스위트
│       └── java/com/.../
│           ├── IntegrationTestSupport.java                # Testcontainers 활용 통합 테스트 기반
│           ├── account/application/AccountTradeConcurrencyTest.java # TransactionTemplate을 활용한 롤오버/데드락 락 충돌 검증
│           ├── account/domain/MonthlyAccountLedgerTest.java         # 당월 이월(Rollover) 로직 검증 단위 테스트
│           ├── portfolio/application/PortfolioQueryServiceTest.java # Reflection/Mock 기반 CQRS 집계 로직 테스트
│           └── portfolio/application/PortfolioViewRefresherTest.java# AFTER_COMMIT 비동기 갱신 쿼리 호출 검증

```
