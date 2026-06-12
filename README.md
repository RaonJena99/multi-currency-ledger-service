# 🏦 다중 자산 포트폴리오 불변 원장 시스템 <br>(Multi-Asset Ledger System)

![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.0.6-brightgreen?logo=springboot&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-Latest-blue?logo=postgresql&logoColor=white)
![Gradle](https://img.shields.io/badge/Gradle-Build-02303A?logo=gradle&logoColor=white)

> 도메인 주도 설계(DDD)와 복식부기 모델을 기반으로 구축된 **엔터프라이즈급 불변 원장 코어 뱅킹 플랫폼**입니다.
> 완벽한 대차평균 정합성을 보장하며, 글로벌 금융 환경에 대응하는 대규모 트래픽 및 다중 자산 처리 아키텍처를 지향합니다.

---

## ✨ 핵심 아키텍처 (Core Architecture)

금융 시스템의 생명인 **데이터 정합성**과 **성능**을 동시에 달성하기 위한 기반 설계입니다.

- 🛡️ **불변 객체 모델링:** `Money` VO(Value Object)를 도입하여 부동소수점 오차 및 이종 통화 간 연산 오류 원천 차단
- 🔒 **견고한 동시성 제어:** 낙관적 락(`@Version`)과 DB 유니크 제약조건을 결합하여 갱신 손실(Lost Update) 방지
- 🔄 **최종 정합성 (Eventual Consistency):** Transactional Outbox 패턴을 통한 비즈니스 로직과 원장 기록의 물리적 분리
- 📦 **기능 기반 패키징 (DDD):** Account, Transaction, Portfolio 등 컨텍스트 단위 분리로 도메인 간 결합도 최소화

---

## 🚀 시스템 진화 로드맵 & 주요 성과

단순 입출금을 넘어, 글로벌 수준의 다중 자산 포트폴리오 처리를 위해 **3단계 아키텍처 고도화**를 완료했습니다.

### [Phase 1] 다중 자산 수용 및 손익 파이프라인 구축

- **가변적 정밀도 제어:** 암호화폐(최대 소수점 18자리) 등 자산별 특성에 맞춘 동적 스케일 지원
- **이종 자산 복식부기:** 환율, 수량, 단가 변동을 원장 테이블에 포괄하여 자산 간 완벽한 교환 정합성 달성
- **손익 분리 모델링:**
  | 분류 | 산출 시점 | 시스템 처리 방식 |
  | :--- | :--- | :--- |
  | **미실현 손익** | 실시간 시장가 반영 | Materialized View 기반 동적 계산 및 인메모리 캐싱 |
  | **실현 손익** | 자산 매도/결제 시점 | 매도 트랜잭션 발생 시 복식부기 원장에 영구 기록 |

### [Phase 2] 비동기 이벤트 기반 원장 동기화

- **Transactional Outbox 패턴:** 계좌 변동 로직과 이벤트(`TradeExecutedEvent`) 발행을 단일 트랜잭션으로 묶어 이벤트 유실률 0% 달성
- **비동기 릴레이 Worker:** 주기적인 폴링 워커가 미처리 이벤트를 원장 서비스로 비동기 전달하여 최종 정합성 보장
- **부패 방지 계층 (ACL):** 상류 이벤트를 원장 내부 규격(`LedgerRecordingCommand`)으로 번역하여 도메인 오염 차단

### [Phase 3] CQRS 기반 읽기 최적화 및 월차 원장 도입

- **월차 원장 (Monthly Ledger) 패턴:** 무한 누적되는 스냅샷 역설을 해결하기 위해 월 단위 생명주기를 가진 원장 도입 (자동 이월/Rollover)
- **CQRS & 구체화된 뷰 (Materialized View):** \* 원장 기록(Write)과 자산 평가(Read) 책임을 물리적으로 분리
  - 트랜잭션 커밋 직후(`AFTER_COMMIT`) 백그라운드에서 View 비동기 갱신(`CONCURRENTLY`)
- **O(1) 실시간 포트폴리오 서빙:** 복잡한 조인 및 손익 계산 로직을 View로 이관하여, 클라이언트 조회 시 DTO 스냅샷만 즉시 반환

---

## 📊 System Architecture

> 본 다이어그램은 CI/CD 파이프라인에 의해 실제 코드로부터 자동 추출된 Living Documentation입니다.

### 1. 시스템 컴포넌트

![System Components](docs/architecture/modulith/components.svg)

### 2. Bounded Context

| Account (계좌 모듈)                                              | Transaction (원장 모듈)                                                  | Portfolio (자산 모듈)                                                |
| :--------------------------------------------------------------- | :----------------------------------------------------------------------- | :------------------------------------------------------------------- |
| ![Account Module](docs/architecture/modulith/module-account.svg) | ![Transaction Module](docs/architecture/modulith/module-transaction.svg) | ![Portfolio Module](docs/architecture/modulith/module-portfolio.svg) |

```plantuml
@startuml
A -> B: Hello
@enduml
```

<details><summary><b>[클릭하여 전체 아키텍처 다이어그램 보기]</b></summary>

```plantuml
@startuml FullArchitecture
!pragma useIntermediatePackages false

class "MultiCurrencyLedgerServiceApplication" as com.github.raonjena99.multi_currency_ledger_service.MultiCurrencyLedgerServiceApplication {
  +void init()
  + {static}void main(String[])
}
class "AccountTradeService" as com.github.raonjena99.multi_currency_ledger_service.account.application.AccountTradeService {
  +UUID buyAsset(UUID, String, AssetType, Money, Money)
  +UUID sellAsset(UUID, String, AssetType, Money, Money)
}
class "MonthlyLedgerResolver" as com.github.raonjena99.multi_currency_ledger_service.account.application.MonthlyLedgerResolver {
  +MonthlyAccountLedger resolveOrInitializeLedger(UUID, String, AssetType, OffsetDateTime)
  +void initializeInNewTransaction(UUID, String, AssetType, String)
}
entity "Account" as com.github.raonjena99.multi_currency_ledger_service.account.domain.Account {
  +UUID getId()
  +String getOwnerName()
  +String getStatus()
}
entity "MonthlyAccountLedger" as com.github.raonjena99.multi_currency_ledger_service.account.domain.MonthlyAccountLedger {
  + {static}MonthlyAccountLedger carryForwardFrom(MonthlyAccountLedger, String)
  +void addBalance(Money, Money)
  +Money subtractBalance(Money)
  +Long getId()
  +UUID getAccountId()
  +String getAssetCode()
  +String getLedgerMonth()
  +Money getBalance()
  +Money getAverageUnitPrice()
  +boolean isCarriedForward()
  +Long getVersion()
}
class "TradeExecutedEvent" as com.github.raonjena99.multi_currency_ledger_service.account.domain.event.TradeExecutedEvent {
  +UUID tradeId()
  +UUID accountId()
  +String assetCode()
  +String assetType()
  +String fiatCode()
  +String tradeType()
  +BigDecimal quantity()
  +BigDecimal unitPrice()
  +BigDecimal exchangeRate()
  +BigDecimal averageCost()
}
interface "AccountRepository" as com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.AccountRepository {
}
interface "MonthlyAccountLedgerRepository" as com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.MonthlyAccountLedgerRepository {
  + {abstract}Optional<MonthlyAccountLedger> findByAccountIdAndAssetCodeAndLedgerMonth(UUID, String, String)
  + {abstract}Optional<MonthlyAccountLedger> findFirstByAccountIdAndAssetCodeOrderByLedgerMonthDesc(UUID, String)
}
class "JpaAuditingConfig" as com.github.raonjena99.multi_currency_ledger_service.common.config.JpaAuditingConfig {
  +DateTimeProvider offsetDateTimeProvider()
}
abstract class "BaseEntity" as com.github.raonjena99.multi_currency_ledger_service.common.domain.BaseEntity {
  +OffsetDateTime getCreatedAt()
}
class "Money" as com.github.raonjena99.multi_currency_ledger_service.common.domain.Money {
  + {static}Money of(String, AssetType)
  + {static}Money zero(AssetType)
  +Money add(Money)
  +Money subtract(Money)
  +Money multiply(BigDecimal)
  +Money divide(BigDecimal)
  +Money negate()
  +boolean isNegative()
  +boolean isZero()
  +int compareTo(Money)
  +BigDecimal getAmount()
  +AssetType getAssetType()
}
class "ErrorResponse" as com.github.raonjena99.multi_currency_ledger_service.common.exception.ErrorResponse {
  +String code()
  +String message()
}
class "GlobalExceptionHandler" as com.github.raonjena99.multi_currency_ledger_service.common.exception.GlobalExceptionHandler {
  +ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException)
  +ResponseEntity<ErrorResponse> handleOptimisticLockingFailure(OptimisticLockingFailureException)
  +ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException)
}
enum "AssetType" as com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType {
  FIAT
  STOCK
  CRYPTO
  __
  +BigDecimal normalize(BigDecimal)
}
enum "EntryType" as com.github.raonjena99.multi_currency_ledger_service.common.model.EntryType {
  DEBIT
  CREDIT
}
entity "OutboxEvent" as com.github.raonjena99.multi_currency_ledger_service.common.outbox.OutboxEvent {
  __
  +void markAsProcessed()
  +void recordFailure(String)
  +Long getId()
  +String getAggregateType()
  +String getAggregateId()
  +String getEventType()
  +String getPayload()
  +LocalDateTime getCreatedAt()
  +boolean isProcessed()
  +int getRetryCount()
  +String getErrorMessage()
  +boolean isDeadLetter()
}
class "OutboxMessageEvent" as com.github.raonjena99.multi_currency_ledger_service.common.outbox.OutboxMessageEvent {
  +String eventType()
  +String payload()
}
class "OutboxRelayWorker" as com.github.raonjena99.multi_currency_ledger_service.common.outbox.OutboxRelayWorker {
  +void relayOutboxEvents()
}
interface "OutboxRepository" as com.github.raonjena99.multi_currency_ledger_service.common.outbox.OutboxRepository {
  + {abstract}List<OutboxEvent> findUnprocessedEvents(Pageable)
  + {abstract}List<OutboxEvent> findTop100ByProcessedFalseOrderByCreatedAtAsc()
}
class "PortfolioQueryService" as com.github.raonjena99.multi_currency_ledger_service.portfolio.application.PortfolioQueryService {
  +PortfolioSummaryResponse getPortfolioSummary(UUID)
}
class "PortfolioViewRefresher" as com.github.raonjena99.multi_currency_ledger_service.portfolio.application.PortfolioViewRefresher {
  +void handleTradeExecuted(TradeExecutedEvent)
}
class "PortfolioSummaryResponse" as com.github.raonjena99.multi_currency_ledger_service.portfolio.application.dto.PortfolioSummaryResponse {
  +UUID accountId()
  +BigDecimal totalAssetValue()
  +BigDecimal totalUnrealizedPnl()
  +List<AssetDetailDto> assets()
}
class "PortfolioSummaryResponse$AssetDetailDto" as com.github.raonjena99.multi_currency_ledger_service.portfolio.application.dto.PortfolioSummaryResponse$AssetDetailDto {
  +String assetCode()
  +BigDecimal quantity()
  +BigDecimal avgUnitPrice()
  +BigDecimal currentMarketPrice()
  +BigDecimal totalValue()
  +BigDecimal unrealizedPnl()
}
entity "CurrentPortfolio" as com.github.raonjena99.multi_currency_ledger_service.portfolio.domain.CurrentPortfolio {
  +String getId()
  +UUID getAccountId()
  +String getAssetCode()
  +BigDecimal getTotalQuantity()
  +BigDecimal getAvgUnitPrice()
  +BigDecimal getCurrentMarketPrice()
  +BigDecimal getUnrealizedPnl()
  +String getLastUpdatedMonth()
}
interface "PortfolioQueryRepository" as com.github.raonjena99.multi_currency_ledger_service.portfolio.infrastructure.PortfolioQueryRepository {
  + {abstract}List<CurrentPortfolio> findAllByAccountId(UUID)
}
class "PortfolioController" as com.github.raonjena99.multi_currency_ledger_service.portfolio.presentation.PortfolioController {
  +ResponseEntity<PortfolioSummaryResponse> getPortfolioSummary(UUID)
}
class "LedgerService" as com.github.raonjena99.multi_currency_ledger_service.transaction.application.LedgerService {
  +void recordDoubleEntry(LedgerRecordingCommand)
}
class "LedgerRecordingCommand" as com.github.raonjena99.multi_currency_ledger_service.transaction.application.command.LedgerRecordingCommand {
  +UUID referenceTradeId()
  +UUID accountId()
  +String assetCode()
  +String fiatCode()
  +String tradeType()
  +Money quantity()
  +Money unitPrice()
  +BigDecimal exchangeRate()
  +Money averageCost()
}
interface "ExchangeRateProvider" as com.github.raonjena99.multi_currency_ledger_service.transaction.application.port.ExchangeRateProvider {
  + {abstract}BigDecimal getExchangeRate(String, String)
}
entity "Transaction" as com.github.raonjena99.multi_currency_ledger_service.transaction.domain.Transaction {
  +void addBuyEntry(UUID, String, Money, Money, BigDecimal)
  +void addSellEntry(UUID, String, Money, Money, BigDecimal, Money)
  +boolean isNew()
  +UUID getId()
  +String getTransactionType()
  +String getDescription()
  +OffsetDateTime getTransactedAt()
  +List<TransactionEntry> getEntries()
}
entity "TransactionEntry" as com.github.raonjena99.multi_currency_ledger_service.transaction.domain.TransactionEntry {
  + {static}TransactionEntry createBuyEntry(Transaction, UUID, String, Money, Money, BigDecimal)
  + {static}TransactionEntry createSellEntry(Transaction, UUID, String, Money, Money, BigDecimal, Money)
  +Long getId()
  +Transaction getTransaction()
  +UUID getAccountId()
  +EntryType getEntryType()
  +String getAssetCode()
  +Money getQuantity()
  +Money getUnitPrice()
  +Money getAmount()
  +Money getRealizedPnl()
  +BigDecimal getExchangeRate()
}
interface "TransactionRepository" as com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.TransactionRepository {
  + {abstract}Optional<Transaction> findWithEntriesById(UUID)
}
class "OrderToLedgerAcl" as com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.acl.OrderToLedgerAcl {
  +void persistOutboxEvent(TradeExecutedEvent)
  +void handleOutboxRelay(OutboxMessageEvent)
}
class "DummyExchangeRateAdapter" as com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.adapter.DummyExchangeRateAdapter {
  +BigDecimal getExchangeRate(String, String)
}
com.github.raonjena99.multi_currency_ledger_service.account.application.AccountTradeService --> com.github.raonjena99.multi_currency_ledger_service.account.application.MonthlyLedgerResolver
com.github.raonjena99.multi_currency_ledger_service.account.application.MonthlyLedgerResolver --> com.github.raonjena99.multi_currency_ledger_service.account.infrastructure.MonthlyAccountLedgerRepository
com.github.raonjena99.multi_currency_ledger_service.account.domain.Account -u-|> com.github.raonjena99.multi_currency_ledger_service.common.domain.BaseEntity
com.github.raonjena99.multi_currency_ledger_service.account.domain.MonthlyAccountLedger -u-|> com.github.raonjena99.multi_currency_ledger_service.common.domain.BaseEntity
com.github.raonjena99.multi_currency_ledger_service.account.domain.MonthlyAccountLedger --> com.github.raonjena99.multi_currency_ledger_service.common.domain.Money
com.github.raonjena99.multi_currency_ledger_service.common.domain.Money --> com.github.raonjena99.multi_currency_ledger_service.common.model.AssetType
com.github.raonjena99.multi_currency_ledger_service.common.outbox.OutboxRelayWorker --> com.github.raonjena99.multi_currency_ledger_service.common.outbox.OutboxRepository
com.github.raonjena99.multi_currency_ledger_service.portfolio.application.PortfolioQueryService --> com.github.raonjena99.multi_currency_ledger_service.portfolio.infrastructure.PortfolioQueryRepository
com.github.raonjena99.multi_currency_ledger_service.portfolio.application.dto.PortfolioSummaryResponse --> com.github.raonjena99.multi_currency_ledger_service.portfolio.application.dto.PortfolioSummaryResponse$AssetDetailDto
com.github.raonjena99.multi_currency_ledger_service.portfolio.presentation.PortfolioController --> com.github.raonjena99.multi_currency_ledger_service.portfolio.application.PortfolioQueryService
com.github.raonjena99.multi_currency_ledger_service.transaction.application.LedgerService --> com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.TransactionRepository
com.github.raonjena99.multi_currency_ledger_service.transaction.application.command.LedgerRecordingCommand --> com.github.raonjena99.multi_currency_ledger_service.common.domain.Money
com.github.raonjena99.multi_currency_ledger_service.transaction.domain.Transaction "0..1" o--o "0..*" com.github.raonjena99.multi_currency_ledger_service.transaction.domain.TransactionEntry
com.github.raonjena99.multi_currency_ledger_service.transaction.domain.TransactionEntry --> com.github.raonjena99.multi_currency_ledger_service.common.model.EntryType
com.github.raonjena99.multi_currency_ledger_service.transaction.domain.TransactionEntry --> com.github.raonjena99.multi_currency_ledger_service.common.domain.Money
com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.acl.OrderToLedgerAcl --> com.github.raonjena99.multi_currency_ledger_service.transaction.application.LedgerService
com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.acl.OrderToLedgerAcl --> com.github.raonjena99.multi_currency_ledger_service.common.outbox.OutboxRepository
com.github.raonjena99.multi_currency_ledger_service.transaction.infrastructure.adapter.DummyExchangeRateAdapter .u.|> com.github.raonjena99.multi_currency_ledger_service.transaction.application.port.ExchangeRateProvider
@enduml
```

</details>

---

## 📂 프로젝트 구조 (Project Structure)

<details>
<summary><b>핵심 디렉토리 구조 펼쳐보기</b></summary>

```text
multi-currency-ledger-service/
├── src/main/java/.../
│   ├── common/                               # [공통] Money VO, Outbox 워커, 전역 예외 처리
│   ├── account/                              # [Write] 월차 원장 기반 매매 트랜잭션 처리 (낙관적 락)
│   │   ├── application/AccountTradeService.java
│   │   └── domain/MonthlyAccountLedger.java
│   ├── portfolio/                            # [Read/CQRS] O(1) 포트폴리오 집계 및 비동기 뷰 갱신
│   │   ├── application/PortfolioQueryService.java
│   │   └── application/PortfolioViewRefresher.java
│   └── transaction/                          # [원장] 복식부기 분개, ACL (부패 방지 계층)
│       ├── application/LedgerService.java
│       └── infrastructure/acl/OrderToLedgerAcl.java
├── src/main/resources/db/migration/          # Flyway 마이그레이션 (월차 원장, Materialized View)
└── src/test/java/.../                        # Testcontainers 기반 롤오버/데드락 통합 테스트 스위트
```
