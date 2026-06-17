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

> 본 다이어그램은 CI/CD 파이프라인에 의해 코드로부터 자동 추출된 Living Documentation입니다.

### 1. 시스템 컴포넌트

![System Components](docs/architecture/modulith/components.svg)

### 2. Bounded Context

| Account (계좌 모듈)                                              | Transaction (원장 모듈)                                                  | Portfolio (자산 모듈)                                                |
| :--------------------------------------------------------------- | :----------------------------------------------------------------------- | :------------------------------------------------------------------- |
| ![Account Module](docs/architecture/modulith/module-account.svg) | ![Transaction Module](docs/architecture/modulith/module-transaction.svg) | ![Portfolio Module](docs/architecture/modulith/module-portfolio.svg) |

### 3. Class Diagram

<details data-auto-diagram="true"><summary><b>[전체 클래스 다이어그램 보기]</b></summary>

```mermaid
classDiagram
  class MultiCurrencyLedgerServiceApplication {
    +void init()
    + void main(String[])
  }
  class AccountTradeService {
    +UUID buyAsset(UUID, String, AssetType, Money, Money)
    +UUID sellAsset(UUID, String, AssetType, Money, Money)
  }
  class MonthlyLedgerResolver {
    +MonthlyAccountLedger resolveOrInitializeLedger(UUID, String, AssetType, OffsetDateTime)
    +void initializeInNewTransaction(UUID, String, AssetType, String)
  }
  class Account {
    +UUID getId()
    +String getOwnerName()
    +String getStatus()
  }
  class MonthlyAccountLedger {
    + MonthlyAccountLedger carryForwardFrom(MonthlyAccountLedger, String)
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
  class TradeExecutedEvent {
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
  class AccountRepository {
    <<Interface>>
  }
  class MonthlyAccountLedgerRepository {
    <<Interface>>
    + Optional~MonthlyAccountLedger~ findByAccountIdAndAssetCodeAndLedgerMonth(UUID, String, String)
    + Optional~MonthlyAccountLedger~ findFirstByAccountIdAndAssetCodeOrderByLedgerMonthDesc(UUID, String)
  }
  class JpaAuditingConfig {
    +DateTimeProvider offsetDateTimeProvider()
  }
  class BaseEntity {
    <<Abstract>>
    +OffsetDateTime getCreatedAt()
  }
  class Money {
    + Money of(String, AssetType)
    + Money zero(AssetType)
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
  class ErrorResponse {
    +String code()
    +String message()
  }
  class GlobalExceptionHandler {
    +ResponseEntity~ErrorResponse~ handleIllegalArgument(IllegalArgumentException)
    +ResponseEntity~ErrorResponse~ handleOptimisticLockingFailure(OptimisticLockingFailureException)
    +ResponseEntity~ErrorResponse~ handleIllegalState(IllegalStateException)
  }
  class AssetType {
    <<Enumeration>>
    FIAT
    STOCK
    CRYPTO
    +BigDecimal normalize(BigDecimal)
  }
  class EntryType {
    <<Enumeration>>
    DEBIT
    CREDIT
  }
  class FailureReason {
    <<Enumeration>>
    AMOUNT_MISMATCH
    TEXT_NOT_FOUND
    TIME_WINDOW_EXCEEDED
    SYSTEM_ERROR
  }
  class SettlementStatus {
    <<Enumeration>>
    PENDING
    MATCHED
    UNMATCHED
    MANUALLY_RESOLVED
  }
  class OutboxEvent {
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
  class OutboxMessageEvent {
    +String eventType()
    +String payload()
  }
  class OutboxRelayWorker {
    +void relayOutboxEvents()
  }
  class OutboxRepository {
    <<Interface>>
    + List~OutboxEvent~ findUnprocessedEvents(Pageable)
    + List~OutboxEvent~ findTop100ByProcessedFalseOrderByCreatedAtAsc()
  }
  class PortfolioQueryService {
    +PortfolioSummaryResponse getPortfolioSummary(UUID)
  }
  class PortfolioViewRefresher {
    +void handleTradeExecuted(TradeExecutedEvent)
  }
  class PortfolioSummaryResponse {
    +UUID accountId()
    +BigDecimal totalAssetValue()
    +BigDecimal totalUnrealizedPnl()
    +List~AssetDetailDto~ assets()
  }
  class PortfolioSummaryResponse_AssetDetailDto {
    +String assetCode()
    +BigDecimal quantity()
    +BigDecimal avgUnitPrice()
    +BigDecimal currentMarketPrice()
    +BigDecimal totalValue()
    +BigDecimal unrealizedPnl()
  }
  class CurrentPortfolio {
    +String getId()
    +UUID getAccountId()
    +String getAssetCode()
    +BigDecimal getTotalQuantity()
    +BigDecimal getAvgUnitPrice()
    +BigDecimal getCurrentMarketPrice()
    +BigDecimal getUnrealizedPnl()
    +String getLastUpdatedMonth()
  }
  class PortfolioQueryRepository {
    <<Interface>>
    + List~CurrentPortfolio~ findAllByAccountId(UUID)
  }
  class PortfolioController {
    +ResponseEntity~PortfolioSummaryResponse~ getPortfolioSummary(UUID)
  }
  class ReconciliationDeadLetterRepository {
    <<Interface>>
    + Page~ReconciliationDeadLetter~ findUnresolvedDeadLetters(Pageable)
  }
  class HeuristicMatchingProcessor {
    +void beforeStep(StepExecution)
    +MatchedReconciliationResult process(ExternalSettlement)
  }
  class MatchedReconciliationResult {
    +ExternalSettlement externalSettlement()
    +Money feeDifference()
  }
  class ReconciliationResultWriter {
    +void write(Chunk~? extends MatchedReconciliationResult~)
  }
  class UnmatchableSettlementException {
    +Throwable fillInStackTrace()
    +String getExternalSettlementId()
  }
  class AmountToleranceRule {
    +int getOrder()
    +RuleResult evaluate(ExternalSettlement, InternalTransactionCandidate)
  }
  class FuzzyTextMatchingRule {
    +int getOrder()
    +RuleResult evaluate(ExternalSettlement, InternalTransactionCandidate)
  }
  class MatchingRule {
    <<Interface>>
    + RuleResult evaluate(ExternalSettlement, InternalTransactionCandidate)
    + int getOrder()
  }
  class RuleResult {
    + RuleResultBuilder builder()
    +boolean isPassed()
    +int getScore()
    +String getFailReason()
  }
  class RuleResult_RuleResultBuilder {
    +RuleResultBuilder passed(boolean)
    +RuleResultBuilder score(int)
    +RuleResultBuilder failReason(String)
    +RuleResult build()
  }
  class TimeToleranceRule {
    +int getOrder()
    +RuleResult evaluate(ExternalSettlement, InternalTransactionCandidate)
  }
  class ManualReconciliationService {
    +void resolveManually(Long, UUID, Money)
  }
  class ExternalSettlement {
    + ExternalSettlement create(String, String, OffsetDateTime, String, Money)
    + ExternalSettlement create(String, String, OffsetDateTime, String, Money, String)
    +void markAsMatched(UUID)
    +void markAsUnmatched()
    +void resolveManually(UUID)
    +UUID getId()
    +OffsetDateTime getSettlementDate()
    +String getExternalReferenceId()
    +String getInstitutionCode()
    +String getDescription()
    +Money getAmount()
    +String getCurrencyCode()
    +SettlementStatus getStatus()
    +UUID getMatchedInternalTransactionId()
  }
  class ExternalSettlementId {
  }
  class ReconciliationDeadLetter {
    + ReconciliationDeadLetter isolate(UUID, FailureReason, String, String)
    +void markAsResolved()
    +Long getId()
    +UUID getExternalSettlementId()
    +FailureReason getFailureReason()
    +String getErrorMessage()
    +boolean isResolved()
    +LocalDateTime getResolvedAt()
    +String getHandlerEnrichmentPayload()
  }
  class ReconciliationFeeAdjustedEvent {
    +UUID settlementId()
    +Money feeDifference()
  }
  class ExternalSettlementRepository {
    <<Interface>>
    + Optional~ExternalSettlement~ findByIdWithoutPartitionKey(UUID)
    + Optional~ExternalSettlement~ findByInstitutionCodeAndExternalReferenceId(String, String)
  }
  class ReconciliationJobConfig {
    +Job monthlyReconciliationJob()
    +Step reconciliationStep()
  }
  class ReconciliationReaderConfig {
    +JpaPagingItemReader~ExternalSettlement~ externalSettlementReader(EntityManagerFactory, String)
  }
  class ReconciliationSkipListener {
    +void onSkipInProcess(ExternalSettlement, Throwable)
  }
  class ReconciliationBatchTxConfig {
    +PlatformTransactionManager batchTransactionManager(DataSource)
  }
  class InternalTransactionCandidate {
    +UUID transactionId()
    +OffsetDateTime transactedAt()
    +String description()
    +Money amount()
  }
  class InternalTransactionQueryDao {
    +List~InternalTransactionCandidate~ fetchCandidatesForPeriod(OffsetDateTime, OffsetDateTime)
  }
  class ReconciliationAdminController {
    +ResponseEntity~Void~ resolveDeadLetter(Long, ManualResolutionRequest)
  }
  class ReconciliationAdminController_ManualResolutionRequest {
    +Money getFeeDifference()
    +UUID internalTransactionId()
    +BigDecimal feeAmount()
    +AssetType feeAssetType()
  }
  class LedgerService {
    +void recordDoubleEntry(LedgerRecordingCommand)
  }
  class LedgerRecordingCommand {
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
  class ExchangeRateProvider {
    <<Interface>>
    + BigDecimal getExchangeRate(String, String)
  }
  class Transaction {
    +void addBuyEntry(UUID, String, Money, Money, BigDecimal)
    +void addSellEntry(UUID, String, Money, Money, BigDecimal, Money)
    +boolean isNew()
    +UUID getId()
    +String getTransactionType()
    +String getDescription()
    +OffsetDateTime getTransactedAt()
    +List~TransactionEntry~ getEntries()
  }
  class TransactionEntry {
    + TransactionEntry createBuyEntry(Transaction, UUID, String, Money, Money, BigDecimal)
    + TransactionEntry createSellEntry(Transaction, UUID, String, Money, Money, BigDecimal, Money)
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
  class TransactionRepository {
    <<Interface>>
    + Optional~Transaction~ findWithEntriesById(UUID)
  }
  class OrderToLedgerAcl {
    +void persistOutboxEvent(TradeExecutedEvent)
    +void handleOutboxRelay(OutboxMessageEvent)
  }
  class ReconciliationToLedgerAcl {
    +void handle(ReconciliationFeeAdjustedEvent)
  }
  class DummyExchangeRateAdapter {
    +BigDecimal getExchangeRate(String, String)
  }
  AccountTradeService --> MonthlyLedgerResolver
  MonthlyLedgerResolver --> MonthlyAccountLedgerRepository
  Account --|> BaseEntity
  MonthlyAccountLedger --|> BaseEntity
  MonthlyAccountLedger --> Money
  Money --> AssetType
  OutboxRelayWorker --> OutboxRepository
  PortfolioQueryService --> PortfolioQueryRepository
  PortfolioSummaryResponse --> PortfolioSummaryResponse_AssetDetailDto
  PortfolioController --> PortfolioQueryService
  HeuristicMatchingProcessor --> InternalTransactionQueryDao
  HeuristicMatchingProcessor --> MatchingRule
  HeuristicMatchingProcessor --> InternalTransactionCandidate
  MatchedReconciliationResult --> ExternalSettlement
  MatchedReconciliationResult --> Money
  AmountToleranceRule ..|> MatchingRule
  FuzzyTextMatchingRule ..|> MatchingRule
  TimeToleranceRule ..|> MatchingRule
  ManualReconciliationService --> ExternalSettlementRepository
  ManualReconciliationService --> ReconciliationDeadLetterRepository
  ExternalSettlement --|> BaseEntity
  ExternalSettlement --> SettlementStatus
  ExternalSettlement --> Money
  ReconciliationDeadLetter --|> BaseEntity
  ReconciliationDeadLetter --> FailureReason
  ReconciliationFeeAdjustedEvent --> Money
  ReconciliationJobConfig --> ExternalSettlement
  ReconciliationJobConfig --> ReconciliationResultWriter
  ReconciliationJobConfig --> HeuristicMatchingProcessor
  ReconciliationJobConfig --> ReconciliationSkipListener
  InternalTransactionCandidate --> Money
  ReconciliationAdminController --> ManualReconciliationService
  ReconciliationAdminController_ManualResolutionRequest --> AssetType
  LedgerService --> TransactionRepository
  LedgerRecordingCommand --> Money
  Transaction "0..1" o-- "0..*" TransactionEntry
  TransactionEntry --> EntryType
  TransactionEntry --> Money
  OrderToLedgerAcl --> LedgerService
  OrderToLedgerAcl --> OutboxRepository
  ReconciliationToLedgerAcl --> LedgerService
  DummyExchangeRateAdapter ..|> ExchangeRateProvider
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
