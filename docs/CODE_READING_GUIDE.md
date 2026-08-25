# 코드 읽기 가이드

> 이 문서는 이 저장소의 **전체 코드를 순서대로 해석하기 위한 학습 경로**입니다.
> 위에서부터 아래로 따라가면, 각 파일이 "왜 그 자리에 있는지"를 알 수 있는 상태에서 코드를 만나게 됩니다.
>
> 총 9개 세션이며, 세션 하나당 30분~1시간 정도를 예상하면 됩니다.
> 세션은 **반드시 순서대로** 읽으십시오. 뒤 세션은 앞 세션의 개념을 이미 안다고 가정합니다.

---

## 0. 읽기 전에 — 머릿속에 먼저 넣을 그림

코드를 열기 전에 이 문장 하나만 외우고 시작하십시오.

> **"잔고를 바꾸는 일"과 "장부에 기록하는 일"이 분리되어 있고, 그 사이를 Kafka가 잇는다.**

이 시스템의 거의 모든 복잡도는 이 분리에서 나옵니다. 전체 흐름은 이렇습니다.

```
[쓰기 경로 — 동기]
HTTP POST /trades/buy
  → AccountTradeController        (인증·소유권·입력 정규화)
  → AccountTradeFacade            (트랜잭션 "밖" 준비: 환율 조회, 원장 생성, 단가 검증)
  → AccountTradeService           (트랜잭션 "안": 멱등성 → 잔고 변경 → 이벤트 발행)
  → MonthlyAccountLedger          (실제 잔고와 평균단가가 바뀌는 곳)
       ↓ (같은 DB 트랜잭션)
  → AccountOutboxAcl → outbox_events 테이블에 INSERT

[비동기 전파]
  → OutboxRelayWorker (5초마다 폴링, SKIP LOCKED)
  → Kafka 토픽 "LedgerRecordingCommand"

[원장 경로 — 비동기]
  → OrderToLedgerAcl (@KafkaListener)
  → LedgerService                 (복식부기 분개 생성 + 대차평균 검증)
  → transactions / transaction_entries 테이블

[읽기 경로 — CQRS]
GET /portfolios/{id}
  → PortfolioQueryService  → Redis 캐시(없으면 DB) + 시세 API → 평가액 계산

[대사 경로 — 월 1회 배치]
  PG 정산 데이터 적재 → 룰 기반 매칭 → 차액 발견 → 다시 원장 경로로 되돌아옴
```

**용어 5개** (이것만 알면 나머지는 코드가 설명해 줍니다):

| 용어 | 뜻 |
| :--- | :--- |
| **월차 원장 (Monthly Ledger)** | 계좌×자산×월 단위로 잔고를 들고 있는 행. 매달 새 행을 만들고 지난달 잔고를 이월한다. |
| **복식부기 (Double-entry)** | 하나의 거래를 항상 차변(Debit)과 대변(Credit) 두 줄로 기록하고, 둘의 합이 같아야 한다는 규칙. |
| **아웃박스 (Outbox)** | 메시지를 브로커에 바로 보내지 않고, 비즈니스 데이터와 **같은 트랜잭션**으로 DB 테이블에 먼저 저장하는 패턴. |
| **ACL (Anti-Corruption Layer)** | 모듈 경계에서 남의 도메인 타입을 내 타입으로 번역하는 계층. 이 프로젝트에서는 `*Acl` 클래스들. |
| **대사 (Reconciliation)** | 외부(PG사) 정산 내역과 내부 거래 기록을 대조해 짝을 맞추는 작업. |

### 읽는 방법에 대한 조언

1. **주석을 무시하지 마십시오.** 이 코드베이스의 주석은 "무엇을 하는가"가 아니라 **"왜 이렇게 하지 않으면 안 되는가"**를 적어둔 것이 대부분입니다. 대개 실제로 터졌던 버그의 기록입니다.
2. **각 세션 끝의 "스스로에게 던질 질문"에 답할 수 있으면 그 세션은 이해한 것입니다.** 답이 막히면 그 파일만 다시 보십시오.
3. **테스트를 같이 여십시오.** 프로덕션 코드를 읽다 막히면 같은 이름의 테스트(`src/test/.../XxxTest.java`)를 여는 것이 가장 빠릅니다. 특히 `src/test/java/.../regression/` 아래 테스트들은 각각이 "과거에 터진 버그 하나"에 대응합니다.

---

## 세션 1 — 공용 언어: 돈을 어떻게 표현하는가

**목표**: 이 시스템에서 "금액"이 무엇인지 이해한다. 이걸 모르면 뒤의 모든 코드가 안 읽힙니다.

### 읽는 순서

**1-1. [common/model/AssetType.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/common/model/AssetType.java)**

자산 종류와 **기본 소수점 자릿수(scale)**를 정의합니다. FIAT=4, STOCK=8, CRYPTO=18, POINT=0.

- 볼 것: enum이 단순 분류가 아니라 `defaultScale`이라는 **동작**을 들고 있다는 점.
- 왜 중요한가: BTC를 소수점 2자리로 자르면 0.005 BTC가 사라집니다. 자산마다 정밀도가 다르다는 것이 이 프로젝트의 출발점입니다.

**1-2. [common/domain/CurrencyScaleResolver.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/common/domain/CurrencyScaleResolver.java)**

"이 자산의 소수점은 몇 자리인가"를 판단하고, 그 자릿수로 값을 정규화(normalize)하는 유틸리티입니다.

- 볼 것:
  - `calculateScale()` — FIAT이면 **자바의 ISO 4217 표준**(`Currency.getInstance(code).getDefaultFractionDigits()`)을 따라갑니다. 그래서 KRW는 0자리, USD는 2자리가 됩니다.
  - `normalize(value, type, code, roundingMode)` — **반올림 방향을 인자로 받는다**는 점이 핵심입니다. 주석에 있는 "고객이 지불하는 금액은 UP, 수취하는 금액은 DOWN"이 이 프로젝트 전체를 관통하는 규칙입니다.
  - `minimumUnit()` — 나중에 최소 거래금액 검증과 대사 허용 오차에서 다시 등장합니다.

**1-3. [common/domain/Money.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/common/domain/Money.java)** ← 이 세션의 핵심

금액 + 자산타입 + 통화코드를 묶은 **불변 값 객체(VO)**입니다. `@Embeddable`이므로 엔티티 안에 컬럼 3개로 펼쳐집니다.

- 볼 것:
  - 생성자에서 **항상** `CurrencyScaleResolver.normalize()`를 호출합니다 → Money 객체는 태어나는 순간 이미 정규화되어 있습니다.
  - `validateSameCurrency()` — `add`/`subtract`/`compareTo`에서 통화가 다르면 예외. 즉 **1 BTC + 1000 KRW 같은 연산이 컴파일이 아니라 런타임에 확실히 막힙니다.**
  - `allocate(int targets)` — 금액을 n분할할 때 자투리를 앞쪽부터 1최소단위씩 나눠줍니다. 합계가 원본과 정확히 일치하도록 하는 고전적 기법입니다.
  - `equals()`가 `compareTo`를 쓰는 이유: `BigDecimal`의 `equals`는 `1.0`과 `1.00`을 다르다고 판정하기 때문입니다.

**1-4. [common/domain/BaseEntity.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/common/domain/BaseEntity.java)**

`createdAt`/`updatedAt` 감사 필드. 짧으니 빠르게 훑고 넘어갑니다. 단, `Account.isNew()`가 `createdAt == null`로 신규 여부를 판정하므로 이 필드가 그냥 장식이 아니라는 것만 기억하십시오.

**1-5. 확인용 테스트**: [MoneyTest.java](../src/test/java/com/github/raonjena99/multi_currency_ledger_service/common/domain/MoneyTest.java), [CurrencyScaleResolverTest.java](../src/test/java/com/github/raonjena99/multi_currency_ledger_service/common/domain/CurrencyScaleResolverTest.java)

### 스스로에게 던질 질문

1. `Money.of(new BigDecimal("100.567"), AssetType.FIAT, "KRW")`의 `amount`는 얼마인가? (힌트: KRW의 ISO 소수점 자릿수)
2. 매수 대금 계산에 `RoundingMode.UP`을 쓰고 매도 대금에 `DOWN`을 쓰면, 시스템 입장에서 무엇이 보장되는가?
3. `Money`가 `@Embeddable`이라는 것은 DB 테이블에 몇 개의 컬럼이 생긴다는 뜻인가?

---

## 세션 2 — 쓰기 경로 (1): 요청이 들어와서 잔고가 바뀔 때까지

**목표**: 매수 API 한 번의 호출을 처음부터 끝까지 따라간다. 이 프로젝트에서 가장 밀도 높은 구간입니다.

### 읽는 순서

**2-1. [account/presentation/AccountTradeController.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/account/presentation/AccountTradeController.java)**

진입점. 짧지만 두 가지가 중요합니다.

- `ownershipGuard.requireOwnership(accountId)` — 인증만으로는 부족하고 **이 계좌가 내 것인지**를 확인합니다.
- `TradeRequestDto`의 **compact 생성자**에서 자산코드를 `trim().toUpperCase()`로 정규화합니다. 주석을 읽으십시오: 이게 없으면 `"btc"`와 `"BTC"`가 서로 다른 원장 행으로 갈라집니다.

**2-2. [account/domain/MonthlyAccountLedger.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/account/domain/MonthlyAccountLedger.java)** ← 먼저 도착지를 봅니다

**실제로 잔고가 저장되는 엔티티**입니다. 서비스 코드를 읽기 전에 이 도메인 객체를 먼저 이해하는 편이 훨씬 빠릅니다.

- 볼 것:
  - 유니크 제약: `(account_id, asset_code, ledger_month)` — 계좌×자산×월당 정확히 한 행.
  - `@Version` — **낙관적 락**. 동시에 두 거래가 같은 행을 수정하면 나중 커밋이 실패합니다.
  - `addBalance()` — **이동평균법**으로 평균단가를 갱신합니다. `(기존 총가치 + 신규 매입가치) / 새 총수량`. 이 세 줄이 손익 계산의 근거가 됩니다.
  - `subtractBalance()` — 잔고를 빼고 **그 시점의 평균단가를 반환**합니다. 이 반환값이 나중에 실현손익 계산에 쓰입니다. 전량 매도 시 평균단가를 0으로 리셋한다는 점에 주목.
  - `carryForwardFrom()` — 지난달 잔고를 이번 달로 이월.
  - `applyAdjustment()` — 잔고 부족 검증을 **하지 않습니다**. 주석에 이유가 있습니다(회계 보정은 고객의 지출이 아니라 사실의 정정이므로 막으면 안 됨).

**2-3. [account/domain/Account.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/account/domain/Account.java)**, **[AccountStatus.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/account/domain/AccountStatus.java)**

간단합니다. `baseCurrency`(계좌의 기준 통화)와 `status`만 기억하면 됩니다. `baseCurrency`는 뒤에서 "평균단가를 어떤 통화로 저장할 것인가"를 결정합니다.

**2-4. [account/application/LedgerPeriodResolver.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/account/application/LedgerPeriodResolver.java)**

"이 거래를 몇 월 장부에 적을 것인가"를 결정합니다. 클래스 주석 전체가 설계 근거이니 반드시 읽으십시오.

- 핵심 규칙: `실효 월 = max(거래 시각의 월, 계좌에 존재하는 최신 원장 월)`
- 왜: 읽기 경로는 최신 월의 행만 봅니다. 쓰기가 과거 월에 기록하면 **거래가 잔고 조회에서 조용히 사라집니다.**
- `withOffsetSameInstant(UTC)`로 정규화하는 이유도 주석에 있습니다(노드 간 시계 편차).

**2-5. [account/application/MonthlyLedgerResolver.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/account/application/MonthlyLedgerResolver.java)** → **[MonthlyLedgerInitializer.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/account/application/MonthlyLedgerInitializer.java)**

"해당 월 원장이 없으면 만든다"를 담당합니다.

- `Initializer`가 별도 클래스인 이유: `@Transactional(propagation = REQUIRES_NEW)`가 **자기 자신을 호출하면 프록시를 안 거쳐서 무시**되기 때문입니다. 이 패턴은 이 코드베이스에 여러 번 등장하므로 여기서 확실히 이해하고 넘어가십시오.
- `findFirstWithLockByAccountIdAndAssetCodeAndLedgerMonthLessThanOrderByLedgerMonthDesc` — 긴 이름이지만 뜻은 "대상 월보다 **이전** 원장 중 가장 최근 것을 락 걸고 가져와라"입니다. `LessThan` 이 빠지면 미래 원장이 과거로 복사됩니다.

**2-6. [account/application/AccountTradeFacade.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/account/application/AccountTradeFacade.java)**

**트랜잭션을 열기 전에 끝내야 하는 일**을 모아둔 계층입니다. 클래스 주석에 존재 이유가 명확히 적혀 있습니다.

- `prepare()` 메서드를 한 줄씩 따라가십시오. 순서 자체가 의미입니다:
  1. `validateAssetTypeConsistency()` — 클라이언트가 보낸 `AssetType`을 믿지 않습니다.
  2. `transactedAt` 고정 — 이후 모든 단계가 이 시각을 공유합니다.
  3. `periodResolver.resolveLedgerMonth()` — 월 결정은 **계좌 단위로 딱 한 번**.
  4. 원장 2개(대상 자산 + 결제 통화) 미리 생성 — 트랜잭션 안에서 하면 커넥션 데드락 위험.
  5. 환율 조회 (외부 HTTP) — **트랜잭션 밖에서** 해야 커넥션 풀이 안 마릅니다.
  6. `validatePriceAgainstMarket()` — 클라이언트가 제시한 단가가 시세에서 10% 이상 벗어나면 거부.
- 왜 재시도(`@Retryable`)가 여기 없고 Service에 있는지, 클래스 주석에서 확인하십시오.

**2-7. [account/application/AccountTradeService.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/account/application/AccountTradeService.java)** ← **이 프로젝트에서 가장 중요한 파일**

트랜잭션 안에서 실제로 잔고를 바꿉니다. `executeBuyAsset()`을 정독하십시오.

- `@Retryable(retryFor = OptimisticLockingFailureException.class)` + `@Transactional` 조합: Spring Retry 어드바이스가 트랜잭션보다 **바깥**이라 재시도마다 새 트랜잭션이 열립니다.
- `registerIdempotencyKey()` — 멱등성 처리. 반드시 읽으십시오:
  - 키를 `accountId:BUY:clientKey`로 **스코프**합니다. 전역 키를 쓰면 남의 키와 충돌하거나, 키 선점 공격이 가능합니다.
  - 이미 있는 키가 **완료된 거래**(`tradeId != null`)를 가리키면 그 거래 ID를 그대로 돌려줍니다(재생/replay). 아직 처리 중이면 409.
- `requireActiveAccount()` — Facade에서 이미 확인했는데 또 합니다. 주석의 **TOCTOU**가 이유입니다.
- `resolveEffectiveMonth()` — 월 경계 경합 대응. 2-4에서 본 로직을 트랜잭션 안에서 한 번 더 확인합니다.
- `requireAboveMinimumNotional()` — KRW 0.4원짜리 거래를 막습니다. 세션 1의 `minimumUnit()`이 여기서 쓰입니다.
- 잔고 변경 두 줄:
  ```java
  fiatLedger.subtractBalance(requiredFiatAmount);      // 돈 나감
  targetAssetLedger.addBalance(buyQuantity, unitPriceInBaseCurrency);  // 자산 들어옴
  ```
- `eventPublisher.publishEvent(event)` — **여기서부터 세션 3으로 이어집니다.**
- 마지막 `idempotency.record().complete(tradeId)` — 같은 트랜잭션이므로 원자적.

**2-8. [account/domain/event/TradeExecutedEvent.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/account/domain/event/TradeExecutedEvent.java)**

발행되는 이벤트의 형태. 특히 `fiatToBaseRate` 주석 — **거래 시점에 실제 적용된 환율을 실어 보내야** 나중에 원장이 잔고와 같은 환율로 기록됩니다.

**2-9. 확인용 테스트**: [AccountTradeServiceTest.java](../src/test/java/com/github/raonjena99/multi_currency_ledger_service/account/application/AccountTradeServiceTest.java), [AccountTradeConcurrencyTest.java](../src/test/java/com/github/raonjena99/multi_currency_ledger_service/account/application/AccountTradeConcurrencyTest.java), [regression/LedgerPeriodIntegrityTest.java](../src/test/java/com/github/raonjena99/multi_currency_ledger_service/regression/LedgerPeriodIntegrityTest.java)

### 스스로에게 던질 질문

1. 환율 조회를 `@Transactional` 메서드 안에서 하면 무슨 일이 생기는가?
2. 낙관적 락 충돌로 재시도될 때, 멱등성 키 INSERT는 어떻게 되는가? (힌트: 어드바이스 순서)
3. 클라이언트가 타임아웃 후 같은 `idempotencyKey`로 재요청하면 응답이 어떻게 다른가 — 거래가 이미 완료된 경우와 처리 중인 경우 각각?
4. 12월 31일 23:59 UTC+9 노드와 UTC 노드가 동시에 거래를 처리하면 어떤 월에 기장되는가?

---

## 세션 3 — 쓰기 경로 (2): 아웃박스와 Kafka

**목표**: 잔고 변경이 어떻게 "확실하게" 원장 모듈로 전달되는지 이해한다.

### 읽는 순서

**3-1. [account/infrastructure/acl/AccountOutboxAcl.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/account/infrastructure/acl/AccountOutboxAcl.java)**

`TradeExecutedEvent`를 받아 `outbox_events` 행으로 저장합니다.

- **`@EventListener`이지 `@TransactionalEventListener`가 아닙니다.** → 거래와 **같은 트랜잭션**에서 실행됩니다. 이것이 아웃박스 패턴의 전부입니다: 잔고 변경과 메시지 저장이 함께 커밋되거나 함께 롤백됩니다.
- 직렬화 실패 시 예외를 삼키지 않는 이유도 같습니다.
- 내부 `record LedgerRecordingPayload` — 모듈 간 타입 결합을 끊기 위한 ACL의 본질.

**3-2. [common/outbox/OutboxEvent.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/common/outbox/OutboxEvent.java)**

메시지 한 건의 상태 머신입니다.

- 필드: `processed`, `retryCount`, `deadLetter`, `lockedAt`, `nextAttemptAt`.
- `recordFailure()` — **지수 백오프**: 30초 → 60초 → … → 최대 10분, 10회 후 데드레터. 주석의 이유가 중요합니다(백오프가 없으면 브로커 몇 분 다운에 전체 이벤트가 데드레터로 빠짐).
- `requeue()` — 데드레터를 되살리는 유일한 경로.

**3-3. [common/outbox/OutboxRepository.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/common/outbox/OutboxRepository.java)**

`findUnprocessedEventsWithSkipLocked` 쿼리를 보십시오. PostgreSQL의 `FOR UPDATE SKIP LOCKED`가 **다중 노드가 같은 행을 집지 않도록** 보장합니다.

**3-4. [common/outbox/OutboxManager.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/common/outbox/OutboxManager.java)**

행을 "선점(claim)"하고 결과를 반영합니다. 5분 이상 잠긴 행은 노드 다운으로 보고 회수합니다.

**3-5. [common/outbox/OutboxRelayWorker.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/common/outbox/OutboxRelayWorker.java)**

5초마다 도는 스케줄러. `@SchedulerLock`이 **일부러 없다**는 주석을 보십시오 — DB의 SKIP LOCKED가 이미 중복을 막으므로, 모든 노드가 동시에 처리하는 편이 처리량에 유리합니다.

- `try/finally` 구조에 주목: 동기 예외가 `finally`를 건너뛰면 성공한 이벤트가 버려지고 중복 발행됩니다.

**3-6. [common/outbox/OutboxMessageDispatcher.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/common/outbox/OutboxMessageDispatcher.java)**

실제 Kafka 전송. **토픽 이름 = `eventType`, 메시지 키 = `aggregateId`(계좌 ID)** 입니다. 계좌 ID를 키로 쓰므로 같은 계좌의 메시지는 같은 파티션 = 순서 보장.

**3-7. [common/config/KafkaConfig.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/common/config/KafkaConfig.java)**

컨슈머 에러 핸들러와 DLT(Dead Letter Topic) 정책이 여기 있습니다.

**3-8. [application.yaml](../src/main/resources/application.yaml)의 `spring.kafka` 블록**

주석이 상세합니다. `enable.idempotence: true`, `auto-offset-reset: earliest`(주석의 이유 확인), `ack-mode: record`.

**3-9. 확인용 테스트**: [OutboxPipelineIntegrationTest.java](../src/test/java/com/github/raonjena99/multi_currency_ledger_service/common/outbox/OutboxPipelineIntegrationTest.java), [regression/OutboxRelayResilienceTest.java](../src/test/java/com/github/raonjena99/multi_currency_ledger_service/regression/OutboxRelayResilienceTest.java)

### 스스로에게 던질 질문

1. 잔고는 변경됐는데 Kafka 전송이 실패하면 어떻게 되는가? 반대로 Kafka 전송은 됐는데 잔고 트랜잭션이 롤백될 수 있는가?
2. `@EventListener`를 `@TransactionalEventListener(AFTER_COMMIT)`으로 바꾸면 어떤 보장이 깨지는가?
3. 노드 3대가 동시에 릴레이를 돌려도 같은 메시지가 3번 발행되지 않는 이유는?
4. 전달 보장 수준은 at-least-once입니다. 그럼 중복 소비는 누가 막는가? (답은 세션 4에)

---

## 세션 4 — 원장 경로: 복식부기가 실제로 일어나는 곳

**목표**: 하나의 거래가 어떻게 두 줄의 분개가 되고, 왜 그 합이 맞아야 하는지 이해한다.

### 읽는 순서

**4-1. [common/model/EntryType.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/common/model/EntryType.java)**

DEBIT(차변) / CREDIT(대변). 회계를 몰라도 됩니다. **"한 거래는 반드시 양쪽에 같은 금액을 적는다"**만 알면 됩니다.

**4-2. [transaction/domain/TransactionEntry.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/transaction/domain/TransactionEntry.java)**

분개 한 줄. 이 파일은 **주석이 코드보다 중요합니다.**

- 생성자: `amount = unitPrice × quantity × exchangeRate` → 항상 **기준 통화(base currency)로 환산된 값**이 저장됩니다.
- `createBuyEntry()` — 차변. 단순합니다.
- `createSellEntry()` — 대변 + **실현손익 계산**. 여기 주석의 "단위 규약"을 반드시 읽으십시오:
  - `sellPrice`는 **결제 통화** 단위
  - `averageCostInBaseCurrency`는 **기준 통화** 단위
  - 이 둘을 그냥 빼면 손익이 환율 배수만큼 틀리는데, **대차는 대수적으로 상쇄되어 정확히 맞습니다.** 즉 검증으로 절대 못 잡습니다. 이런 종류의 버그가 왜 무서운지 보여주는 좋은 예입니다.
- `toDbScale()` — 자바 계산과 DB `numeric(36,18)`을 미리 맞춥니다.

**4-3. [transaction/domain/Transaction.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/transaction/domain/Transaction.java)**

분개들의 묶음(Aggregate Root).

- `verifyDoubleEntry()` — **통화별로** 차변 합계와 대변 합계를 비교합니다. 실현손익은 대변에 가산됩니다.
- `@PrePersist`/`@PreUpdate`로도 호출하지만, 주석에 있듯 **콜백만 믿으면 안 됩니다**(부모 행이 dirty하지 않으면 `@PreUpdate`가 안 뜀). 그래서 `LedgerService`가 저장 직전에 **명시적으로** 한 번 더 부릅니다.
- `record(id, type, desc, transactedAt)` — 시각을 주입받는 오버로드가 있는 이유: 원장 기록은 Kafka 소비 시점(비동기)이라, 주입하지 않으면 **소비 시각**이 기록되어 월차 원장의 귀속월과 어긋납니다.

**4-4. [transaction/infrastructure/acl/OrderToLedgerAcl.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/transaction/infrastructure/acl/OrderToLedgerAcl.java)**

Kafka 컨슈머. 짧습니다.

- MDC에 correlation id를 복원 → 세션 8에서 다시 봅니다.
- 예외를 **그대로 다시 던집니다** → Spring Kafka의 `DefaultErrorHandler`가 재시도/DLT로 보냅니다.

**4-5. [transaction/application/command/LedgerRecordingCommand.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/transaction/application/command/LedgerRecordingCommand.java)**

JSON 페이로드가 역직렬화되는 대상. 3-1의 `LedgerRecordingPayload`와 필드가 대응하는지 비교해 보십시오.

**4-6. [transaction/application/LedgerService.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/transaction/application/LedgerService.java)** ← 두 번째로 중요한 파일

`recordDoubleEntry(cmd)`를 정독하십시오.

- 맨 위 `transactionRepository.existsById(cmd.referenceTradeId())` — **이것이 세션 3 질문 4의 답입니다.** 거래 ID로 중복 소비를 흡수합니다.
- 거래 유형별 분개 조립:
  - `BUY`: 차변=자산 증가, 대변=법정화폐 감소
  - `SELL`: 차변=법정화폐 증가, 대변=자산 감소(+실현손익)
  - `FEE_DEDUCTION`: 고객 → 시스템 수수료 계정
  - `FEE_ADJUSTMENT`: 대사에서 발견된 차액 보정. **여기서 `accountApi.applyFiatBalanceAdjustment()`를 호출해 잔고에도 반영합니다.** 세션 7과 이어지는 지점입니다.
- 반올림 방향이 `AccountTradeService`와 **반드시 같아야** 한다는 주석(BUY=UP, SELL=DOWN)을 확인하십시오.
- `plugRoundingResidual()` — 이 프로젝트에서 가장 섬세한 부분입니다:
  - 차변−대변 차액이 0이 아니면, `allowedRoundingResidual()`이 계산한 **반올림으로 설명 가능한 한도** 안인지 봅니다.
  - 한도 안이면 `SYSTEM_FX_GAIN/LOSS` 계정으로 흘려보내고, **그 크기를 지표로 기록**합니다.
  - 한도를 넘으면 예외. 주석의 "예전 구현은 차액을 무제한 흡수해서 실제 버그가 숨었다"가 이 설계의 이유입니다.
  - `allowedRoundingResidual()`이 왜 단순히 "엔트리 수 × 최소단위"가 아닌지(환율 증폭) 주석에서 확인하십시오.

**4-7. [transaction/infrastructure/acl/LedgerDltConsumer.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/transaction/infrastructure/acl/LedgerDltConsumer.java)** + **[transaction/domain/LedgerDeadLetter.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/transaction/domain/LedgerDeadLetter.java)**

원장 기록이 완전히 실패한 메시지의 종착지. 주석의 "잔고는 변경되었는데 대응하는 분개가 없다"가 이 컴포넌트의 존재 이유입니다.

**4-8. 확인용 테스트**: [LedgerServiceTest.java](../src/test/java/com/github/raonjena99/multi_currency_ledger_service/transaction/application/LedgerServiceTest.java), [e2e/TradeToLedgerE2ETest.java](../src/test/java/com/github/raonjena99/multi_currency_ledger_service/e2e/TradeToLedgerE2ETest.java) ← **이 E2E 테스트를 꼭 읽으십시오.** 세션 2~4를 한 줄기로 꿰어 줍니다.

### 스스로에게 던질 질문

1. `1 BTC`를 `50,000 USD`에 매수하고 계좌 기준 통화가 `KRW`일 때, 분개 두 줄의 `amount`는 각각 무엇이고 어떤 통화인가?
2. `verifyDoubleEntry()`가 통화별로 나눠서 검증하는 이유는?
3. 실현손익을 잘못 계산해도 대차가 맞을 수 있다. 그럼 그 버그는 무엇으로 잡는가?
4. 같은 Kafka 메시지가 3번 소비되면 `transactions` 테이블에 몇 행이 생기는가?

---

## 세션 5 — 읽기 경로: CQRS와 캐시

**목표**: 잔고가 어떻게 "조회"되는지, 왜 쓰기와 다른 길을 가는지 이해한다.

### 읽는 순서

**5-1. [account/AccountApi.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/account/AccountApi.java)**

Account 모듈이 **외부에 공개하는 유일한 인터페이스**입니다. Spring Modulith에서 모듈 루트 패키지에 있는 타입만 공개(public)이고 하위 패키지는 내부라는 규칙이 적용됩니다.

**5-2. [account/application/AccountApiImpl.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/account/application/AccountApiImpl.java)**

- `getBalances()` → `findLatestBalancesByAccountId()` 호출.
- `applyFiatBalanceAdjustment()` → 4-6에서 봤던 대사 보정의 실제 구현. `BalanceAdjustedEvent`를 발행합니다.

**5-3. [account/infrastructure/MonthlyAccountLedgerRepository.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/account/infrastructure/MonthlyAccountLedgerRepository.java)**

`findLatestBalancesByAccountId`와 `findLatestLedgerMonthByAccountId`의 쿼리를 직접 보십시오. **`MAX(ledger_month)` 기준**이지 `MAX(id)` 기준이 아닙니다. 시퀀스 `allocationSize = 50` 때문에 id 순서와 월 순서가 어긋날 수 있기 때문입니다(README에도 명시).

**5-4. [portfolio/domain/PortfolioValuation.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/portfolio/domain/PortfolioValuation.java)**

미실현 손익 = `(현재가 − 기준통화 환산 평균단가) × 수량`. 짧고 순수한 계산 로직.

**5-5. [portfolio/application/dto/](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/portfolio/application/dto/) 두 파일**

`PortfolioCacheDto`(Redis에 들어가는 형태) / `PortfolioSummaryResponse`(API 응답). 훑고 넘어갑니다.

**5-6. [portfolio/application/port/PortfolioCachePort.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/portfolio/application/port/PortfolioCachePort.java)** → **[infrastructure/cache/RedisPortfolioCacheAdapter.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/portfolio/infrastructure/cache/RedisPortfolioCacheAdapter.java)**

포트-어댑터 구조. Redis 의존성이 도메인으로 새지 않게 하는 경계입니다.

**5-7. [portfolio/application/PortfolioQueryService.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/portfolio/application/PortfolioQueryService.java)**

- 클래스 주석부터: **`@Transactional`을 일부러 안 씁니다.** 락 대기 3초 동안 Hikari 커넥션(풀 20)을 붙잡으면 스스로 서비스를 마비시키기 때문입니다.
- `loadSnapshot()`의 3단 구조: 캐시 읽기 → 락 획득(스핀) → Double-Checked Locking → DB 재구성 → 캐시 저장.
- 환율은 `getExchangeRates()`로 **한 번에 배치 조회**합니다(N+1 방지).
- 환율을 못 구한 자산을 **조용히 빼지 않는** 이유가 주석에 있습니다: 1 BTC 보유 계좌가 총액 0으로 표시되면 빈 계좌와 구분되지 않습니다. 대신 `isStaleData` 플래그를 세웁니다.
- `readCacheQuietly()` — Redis 장애를 **캐시 미스로 강등**합니다.

**5-8. [portfolio/application/PortfolioViewRefresher.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/portfolio/application/PortfolioViewRefresher.java)**

캐시 갱신(Write-Through).

- `@Async @TransactionalEventListener(AFTER_COMMIT)` — 세션 3의 `@EventListener`와 **대조**하십시오. 여기는 커밋 후여야 합니다(아직 커밋 안 된 잔고를 캐시에 넣으면 안 되므로).
- 클래스 주석의 **실패 시 불변식**이 핵심입니다: 갱신에 실패하면 **반드시 캐시를 삭제**해야 합니다. 그대로 두면 TTL 1시간 동안 옛날 잔고가 서빙됩니다.

**5-9. [portfolio/presentation/PortfolioController.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/portfolio/presentation/PortfolioController.java)**

**5-10. 확인용 테스트**: [PortfolioQueryServiceTest.java](../src/test/java/com/github/raonjena99/multi_currency_ledger_service/portfolio/application/PortfolioQueryServiceTest.java), [PortfolioViewRefresherTest.java](../src/test/java/com/github/raonjena99/multi_currency_ledger_service/portfolio/application/PortfolioViewRefresherTest.java)

### 스스로에게 던질 질문

1. 조회 서비스가 트랜잭션을 열면 왜 장애가 나는가? 실제 DB 접근은 누가 하는가?
2. 캐시 갱신 실패 시 "캐시를 그대로 두기"와 "삭제하기" 중 왜 후자가 안전한가?
3. `PortfolioViewRefresher`는 `AFTER_COMMIT`인데 `AccountOutboxAcl`은 아니다. 둘의 요구사항 차이는?

---

## 세션 6 — 외부 시세: 포트/어댑터와 복원력

**목표**: 통제할 수 없는 외부 시스템을 어떻게 격리하는지 이해한다.

### 읽는 순서

**6-1. [common/port/ExchangeRateProvider.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/common/port/ExchangeRateProvider.java)**

포트 인터페이스. `record ExchangeRate(BigDecimal rate, boolean isStale)` — **"낡은 데이터인가"를 반환값에 담는다**는 설계가 핵심입니다. 이 플래그가 거래 차단(세션 2)과 응답 표시(세션 5)로 각각 흘러갑니다.

**6-2. [common/infrastructure/adapter/MarketDataRouter.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/common/infrastructure/adapter/MarketDataRouter.java)**

**포트를 구현하는 유일한 클래스**입니다. 클래스 주석의 "왜 어댑터가 아니라 라우터가 포트를 구현하는가"를 읽으십시오 — `@Primary`가 프로파일 게이팅을 이겨서 개발 환경에서 실서비스 어댑터가 붙었던 사고의 결과입니다.

- 분기 규칙 4단계(같은 코드 → 암호화폐 → 법정화폐 → 예외)를 확인.
- `@Profile("!local & !test & !dev")` — 개발/테스트에서는 이 빈이 아예 안 뜹니다.
- `getExchangeRates()` — 캐시 먼저, 암호화폐는 CoinGecko 다중 조회로 1회 호출에 묶기.

**6-3. [common/infrastructure/adapter/ExchangeRateCache.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/common/infrastructure/adapter/ExchangeRateCache.java)**

모든 어댑터가 공유하는 캐시.

- `MAX_AGE = 5분` — 이보다 오래된 시세는 신선하지 않다고 봅니다.
- **역방향 동시 기록**이 이 클래스의 핵심 기능입니다. 주석의 실측 데이터(`KRW→BTC` 참값 `9.3461e-9`를 `9e-9`로 반환, 오차 3.7%)를 읽으십시오. 값이 큰 방향으로 조회하고 역수를 `BigDecimal`로 계산합니다.

**6-4. [common/infrastructure/adapter/FxRatesApiAdapter.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/common/infrastructure/adapter/FxRatesApiAdapter.java)**

법정화폐 어댑터. `@CircuitBreaker` + `@Retry` 애노테이션의 **순서**와 `fallbackMethod`가 어디에 붙었는지 확인하십시오. README에 명시된 규칙: **`fallbackMethod`는 가장 바깥 애노테이션에만.** 안쪽 `@Retry`에도 붙이면 폴백이 예외를 삼켜 서킷 브레이커가 실패를 관측하지 못합니다.

**6-5. [common/infrastructure/adapter/CoinGeckoAdapter.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/common/infrastructure/adapter/CoinGeckoAdapter.java)** + **[CryptoAssetProperties.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/common/infrastructure/adapter/CryptoAssetProperties.java)**

암호화폐 어댑터. `symbol-ids` 맵이 **라우팅 기준도 겸한다**는 점이 중요합니다(여기 없는 코드는 ISO 통화가 아니면 거부).

**6-6. [common/infrastructure/adapter/DummyExchangeRateAdapter.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/common/infrastructure/adapter/DummyExchangeRateAdapter.java)**

local/test/dev 프로파일용 대체 구현. 6-2의 `@Profile`과 짝을 이룹니다.

**6-7. [common/config/RestClientConfig.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/common/config/RestClientConfig.java)** + **[application.yaml](../src/main/resources/application.yaml)의 `resilience4j` 블록**

타임아웃 값과 서킷 브레이커 설정. 특히:
- `read-timeout: 3s`가 `slowCallDurationThreshold: 2000ms`보다 **약간 길게** 설정된 이유(주석에 있음).
- `ignoreExceptions`에 `UnsupportedAssetCodeException`이 들어간 이유(영구 오류를 장애로 집계하면 안 됨).

**6-8. [common/exception/ArbitrageRiskException.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/common/exception/ArbitrageRiskException.java)**

5분 넘은 환율로 거래를 차단할 때 던지는 예외. 왜 "차익거래 위험"인지 생각해 보십시오.

**6-9. 확인용 테스트**: [MarketDataRouterTest.java](../src/test/java/com/github/raonjena99/multi_currency_ledger_service/common/infrastructure/adapter/MarketDataRouterTest.java), [ExchangeRateCacheTest.java](../src/test/java/com/github/raonjena99/multi_currency_ledger_service/common/infrastructure/adapter/ExchangeRateCacheTest.java)

### 스스로에게 던질 질문

1. 시세 API가 완전히 죽으면 (a) 거래, (b) 포트폴리오 조회는 각각 어떻게 되는가?
2. 어댑터마다 `ExchangeRateProvider`를 구현했다면 어떤 사고가 가능한가?
3. `KRW→BTC` 환율을 왜 직접 묻지 않고 `BTC→KRW`를 물어 역수를 취하는가?

---

## 세션 7 — 대사(Reconciliation): 배치 파이프라인

**목표**: 외부 정산 데이터와 내부 거래를 짝짓는 전체 파이프라인을 이해한다. 여기까지 오면 시스템이 한 바퀴 돕니다.

> **먼저 큰 그림**:
> ```
> PG API → SettlementIngestionService → external_settlements 테이블 (PENDING)
>                                              ↓ (월 1회 배치)
>   Reader → HeuristicMatchingProcessor(룰 평가) → ReconciliationResultWriter
>                     ↓ 실패                              ↓ 성공
>            ReconciliationSkipListener              SettlementMatch 기록
>                     ↓                                   ↓ 차액 있으면
>            reconciliation_dead_letters      ReconciliationFeeAdjustedEvent
>                     ↓                                   ↓
>            관리자 API(수동 대사)              ReconciliationToLedgerAcl → 아웃박스
>                                                         ↓
>                                            LedgerService(FEE_ADJUSTMENT) → 잔고 보정
> ```

### 읽는 순서

**7-1. [reconciliation/domain/ExternalSettlement.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/reconciliation/domain/ExternalSettlement.java)** + **[ExternalSettlementId.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/reconciliation/domain/ExternalSettlementId.java)** + **[common/model/SettlementStatus.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/common/model/SettlementStatus.java)**

외부 정산 한 건. 복합키(`id` + `settlementDate`)인 이유는 **테이블 파티셔닝** 때문입니다 — [V202607101205__create_external_settlement_partitions.sql](../src/main/resources/db/migration/V202607101205__create_external_settlement_partitions.sql)을 같이 여십시오. 상태 전이(`PENDING → MATCHED / UNMATCHED`)를 메서드로 확인.

**7-2. [reconciliation/infrastructure/adapter/PgSettlementAdapter.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/reconciliation/infrastructure/adapter/PgSettlementAdapter.java)** + **[ExternalSettlementDto.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/reconciliation/infrastructure/adapter/ExternalSettlementDto.java)**

PG사 API 호출. 세션 6과 같은 서킷 브레이커 패턴입니다.

**7-3. [reconciliation/application/ingestion/SettlementIngestionService.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/reconciliation/application/ingestion/SettlementIngestionService.java)** + **[SettlementRecorder.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/reconciliation/application/ingestion/SettlementRecorder.java)**

적재. 두 클래스로 나뉜 이유는 세션 2-5에서 본 것과 같습니다(`REQUIRES_NEW`와 프록시).

- **실패율 임계값(10%)**이 있는 이유가 주석에 있습니다: 건별 격리는 산발적 실패용이지, PG 전면 장애까지 "성공"으로 보고하기 위한 것이 아닙니다.

**7-4. [reconciliation/infrastructure/query/InternalTransactionCandidate.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/reconciliation/infrastructure/query/InternalTransactionCandidate.java)** + **[InternalTransactionQueryDao.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/reconciliation/infrastructure/query/InternalTransactionQueryDao.java)**

"내부 거래 후보"를 뽑는 조회 전용 DAO. 세션 4에서 만든 `transactions`/`transaction_entries`를 읽습니다.

**7-5. 룰 엔진 — 4개 파일을 이 순서로**

1. [application/rule/MatchingRule.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/reconciliation/application/rule/MatchingRule.java) — 인터페이스. `evaluate()` + `getOrder()`.
2. [application/rule/RuleResult.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/reconciliation/application/rule/RuleResult.java) — `passed` / `score` / `failReason`.
3. [application/rule/TimeToleranceRule.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/reconciliation/application/rule/TimeToleranceRule.java) (order=1) — ±3일. UTC 정규화에 주목.
4. [application/rule/AmountToleranceRule.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/reconciliation/application/rule/AmountToleranceRule.java) (order=2) — **가장 중요한 룰.** 클래스 주석을 읽으십시오: 허용 오차를 통화 독립 상수(`100`)로 두면 **100 BTC 차이가 "일치"로 판정**됩니다. 대신 `max(비율 오차, 통화 최소단위 × 배수)`를 씁니다. 세션 1의 `minimumUnit()`이 여기서 다시 쓰입니다.
5. [application/rule/FuzzyTextMatchingRule.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/reconciliation/application/rule/FuzzyTextMatchingRule.java) (order=3) — 레벤슈타인 거리. **관문이 아니라 순위 결정용 점수**라는 주석이 핵심입니다(항상 `passed(true)`).

**7-6. [reconciliation/application/batch/HeuristicMatchingProcessor.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/reconciliation/application/batch/HeuristicMatchingProcessor.java)**

룰들을 실제로 돌리는 곳.

- 생성자에서 `getOrder()`로 **정렬**합니다 — 무거운 레벤슈타인이 먼저 돌면 안 되니까요.
- `dailyCandidatesCache` — 최대 14일치를 들고 있는 LRU(`LinkedHashMap` + `removeEldestEntry`).
- 대상일 ±3일 = 7일치를 검색 공간으로 구성.
- **모호성 가드**: 모든 룰을 통과한 후보가 2개 이상이면 `AMBIGUOUS_MATCH`로 던져 수동 검토로 보냅니다. 오매칭보다 미매칭이 낫다는 판단입니다.
- 매칭 성공 시 그 후보를 캐시에서 **제거**합니다(1:1 매칭 보장).
- `@AfterChunkError` — 청크 롤백 시 메모리 캐시는 자동 롤백이 안 되므로 통째로 비웁니다.

**7-7. [reconciliation/application/batch/MatchedReconciliationResult.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/reconciliation/application/batch/MatchedReconciliationResult.java)** + **[application/exception/UnmatchableSettlementException.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/reconciliation/application/exception/UnmatchableSettlementException.java)**

**7-8. [reconciliation/application/batch/SettlementMatchRecorder.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/reconciliation/application/batch/SettlementMatchRecorder.java)**

1:1 매칭을 **DB 유니크 제약**으로 확정합니다. 클래스 주석의 세 가지 이유(독립 트랜잭션, 즉시 flush, 멱등성)를 읽으십시오.

- `MatchOutcome` enum 3종의 차이가 핵심입니다: `RECORDED` / `ALREADY_RECORDED`(내가 남긴 것, 재실행) / `TAKEN_BY_ANOTHER`(남이 선점).
- 고아 매칭 행 정리 로직도 확인.

**7-9. [reconciliation/infrastructure/batch/ReconciliationResultWriter.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/reconciliation/infrastructure/batch/ReconciliationResultWriter.java)**

성공 결과 반영 + 차액 발견 시 `ReconciliationFeeAdjustedEvent` 발행. `isolateTakenSettlement()`이 왜 상태 전이만으로 끝내지 않고 데드레터도 남기는지 주석 확인.

**7-10. [reconciliation/infrastructure/batch/ReconciliationSkipListener.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/reconciliation/infrastructure/batch/ReconciliationSkipListener.java)** + **[PgApiSkipListener.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/reconciliation/infrastructure/batch/PgApiSkipListener.java)** + **[domain/ReconciliationDeadLetter.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/reconciliation/domain/ReconciliationDeadLetter.java)** + **[common/model/FailureReason.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/common/model/FailureReason.java)**

실패 건의 격리 경로. 비즈니스 실패와 통신 장애를 **다른 리스너로 분리**한 이유를 생각해 보십시오.

**7-11. [reconciliation/infrastructure/batch/ReconciliationReaderConfig.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/reconciliation/infrastructure/batch/ReconciliationReaderConfig.java)** → **[ReconciliationJobConfig.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/reconciliation/infrastructure/batch/ReconciliationJobConfig.java)**

이제 조각들을 조립합니다. 청크 1000, skip 대상 예외 3종, 비즈니스/인프라 스킵 한도 분리(50,000 vs 100)를 확인.

**7-12. [reconciliation/infrastructure/scheduler/ReconciliationJobScheduler.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/reconciliation/infrastructure/scheduler/ReconciliationJobScheduler.java)** + **[common/config/ShedLockConfig.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/common/config/ShedLockConfig.java)**

트리거. `spring.batch.job.enabled: false`이므로 **이 스케줄러가 없으면 잡은 영원히 안 돕니다.** `@SchedulerLock`으로 다중 노드 중 한 대에서만 실행.

> 세션 3의 `OutboxRelayWorker`는 일부러 `@SchedulerLock`을 **안 썼습니다.** 왜 여기는 쓰고 거기는 안 쓰는지 비교해 보십시오.

**7-13. [transaction/infrastructure/acl/ReconciliationToLedgerAcl.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/transaction/infrastructure/acl/ReconciliationToLedgerAcl.java)**

**여기서 한 바퀴가 닫힙니다.** 대사 차액 이벤트 → 아웃박스 → Kafka → `LedgerService`의 `FEE_ADJUSTMENT` 분기(4-6) → `accountApi.applyFiatBalanceAdjustment()`(5-2) → `BalanceAdjustedEvent` → `PortfolioViewRefresher`(5-8).

**7-14. [reconciliation/application/service/ManualReconciliationService.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/reconciliation/application/service/ManualReconciliationService.java)** + **[presentation/ReconciliationAdminController.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/reconciliation/presentation/ReconciliationAdminController.java)** + **[common/outbox/OutboxAdminController.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/common/outbox/OutboxAdminController.java)**

백오피스 경로. 자동화가 실패한 건을 사람이 처리하는 출구입니다.

**7-15. 확인용 테스트**: [HeuristicMatchingProcessorTest.java](../src/test/java/com/github/raonjena99/multi_currency_ledger_service/reconciliation/application/batch/HeuristicMatchingProcessorTest.java), [ReconciliationJobIntegrationTest.java](../src/test/java/com/github/raonjena99/multi_currency_ledger_service/reconciliation/infrastructure/batch/ReconciliationJobIntegrationTest.java), [regression/SettlementMatchIntegrityTest.java](../src/test/java/com/github/raonjena99/multi_currency_ledger_service/regression/SettlementMatchIntegrityTest.java)

### 스스로에게 던질 질문

1. 정산 1건이 내부 거래 2건과 모두 매칭될 수 있을 때 시스템은 어떻게 하는가? 왜 그 선택인가?
2. 청크가 롤백되고 건별로 재실행될 때, 이미 커밋된 `SettlementMatch` 행 때문에 무슨 문제가 생길 수 있는가? 어떻게 막았는가?
3. 텍스트 유사도 룰을 필수 관문으로 만들면 무슨 일이 생기는가?
4. 대사에서 발견한 수수료 차액이 고객 잔고에 반영되기까지 거치는 컴포넌트를 순서대로 나열해 보십시오.

---

## 세션 8 — 횡단 관심사: 보안·추적·예외·지표

**목표**: 모든 요청이 공통으로 지나가는 층을 이해한다.

### 읽는 순서

**8-1. 보안 4파일**

1. [common/security/LedgerPrincipal.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/common/security/LedgerPrincipal.java) — 인증 주체(subject, accountId, admin).
2. [common/security/PrincipalResolver.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/common/security/PrincipalResolver.java) — 확장 지점. JWT를 직접 검증하려면 이 인터페이스만 구현하면 기본 구현체가 물러납니다.
3. [common/security/HeaderPrincipalResolver.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/common/security/HeaderPrincipalResolver.java) — 기본 구현. **게이트웨이가 주입한 `X-Auth-*` 헤더를 읽습니다.** 이 서비스는 토큰 발급/검증 주체가 아닙니다. `X-Gateway-Secret` 검증 로직도 확인.
4. [common/security/PrincipalAuthenticationFilter.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/common/security/PrincipalAuthenticationFilter.java) → [SecurityConfig.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/common/security/SecurityConfig.java) → [AccountOwnershipGuard.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/common/security/AccountOwnershipGuard.java)

`SecurityConfig`는 **URL 단위 권한**만 정합니다. **소유권은 URL로 표현할 수 없어서** `AccountOwnershipGuard`가 컨트롤러 진입 시점에 따로 확인합니다(2-1에서 봤던 그 호출).

**8-2. 분산 추적 2파일**

1. [common/telemetry/CorrelationIdFilter.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/common/telemetry/CorrelationIdFilter.java) — HTTP 진입점에서 MDC에 심습니다. `sanitize()`가 로그 위조(log forging)를 막는 부분을 보십시오.
2. [common/telemetry/KafkaCorrelationInterceptor.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/common/telemetry/KafkaCorrelationInterceptor.java) — `onSend` 시점에 MDC를 읽어 Kafka 헤더로 옮깁니다.

체인을 되짚어 보십시오: `CorrelationIdFilter`(MDC) → `AccountOutboxAcl`(DB 컬럼) → `OutboxMessageDispatcher`(MDC 복원) → `KafkaCorrelationInterceptor`(헤더) → `OrderToLedgerAcl`(MDC 복원). **키 문자열이 한 군데라도 다르면 조용히 끊깁니다** — 그래서 모두 `CorrelationIdFilter.MDC_KEY` 상수를 참조합니다.

**8-3. [common/exception/](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/common/exception/) 전체**

먼저 [GlobalExceptionHandler.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/common/exception/GlobalExceptionHandler.java)를 읽고, **거기서 매핑되는 예외들을 역으로 훑으십시오.** 각 예외가 어떤 HTTP 상태와 에러 코드로 나가는지 표로 정리해 보면 도메인 규칙이 한눈에 들어옵니다.

| 예외 | 대략적 의미 |
| :--- | :--- |
| `InsufficientBalanceException` | 잔고 부족 |
| `DuplicateTradeRequestException` | 처리 중인 중복 요청 (409) |
| `BelowMinimumNotionalException` | 통화 최소 단위 미만 |
| `DoubleEntryImbalanceException` | 대차 불일치 — **버그 신호** |
| `ArbitrageRiskException` | 시세가 너무 낡음 (503) |
| `UnsupportedAssetCodeException` | 시세 공급자 없음 (422) |
| `AccountNotFoundException` | 404 |
| `InvalidAccountStateException` | 정지/해지 계좌 |

[ErrorResponse.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/common/exception/ErrorResponse.java)도 함께.

**8-4. 설정 4파일**: [JpaConfig.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/common/config/JpaConfig.java), [RedisConfig.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/common/config/RedisConfig.java), [RestClientConfig.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/common/config/RestClientConfig.java), [KafkaConfig.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/common/config/KafkaConfig.java)

**8-5. 지표 2파일**: [account/application/AccountMetricsConfiguration.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/account/application/AccountMetricsConfiguration.java), [account/application/IdempotencyCleanupWorker.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/account/application/IdempotencyCleanupWorker.java)

지금까지 코드 곳곳에서 본 커스텀 지표들을 정리하십시오. 각 지표가 **어떤 버그를 감시하려고** 존재하는지가 핵심입니다:

- `ledger.period.backdated_write_redirected` (2-4) — 노드 간 시계 편차
- `ledger.rounding_residual.plugged` (4-6) — 반올림으로 위장한 계산 버그
- `ledger.dead_letter.count` (4-7) — 잔고와 원장의 불일치

**8-6. [application.yaml](../src/main/resources/application.yaml) 전체를 한 번 정독**

세션 1~8을 다 읽은 지금이면, 이 파일의 주석 하나하나가 어떤 코드를 가리키는지 보일 것입니다.

**8-7. [MultiCurrencyLedgerServiceApplication.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/MultiCurrencyLedgerServiceApplication.java)** + **[common/package-info.java](../src/main/java/com/github/raonjena99/multi_currency_ledger_service/common/package-info.java)** + 각 모듈의 `package-info.java`

Spring Modulith의 모듈 선언이 여기 있습니다. `package-info.java`가 왜 코드 없이 존재하는지 확인하십시오.

### 스스로에게 던질 질문

1. 이 서비스는 JWT를 검증하는가? 아니라면 인증은 누가 책임지고, 그 전제가 깨지면 어떤 위험이 있는가?
2. `X-Correlation-Id`를 클라이언트가 `"abc\ninjected log line"`으로 보내면 어떻게 되는가?
3. `DoubleEntryImbalanceException`이 프로덕션에서 발생했다면 무엇을 의심해야 하는가?

---

## 세션 9 — 스키마와 테스트: 코드가 지키는 약속

**목표**: DB 제약과 테스트가 어떤 불변식을 강제하는지 확인하며 전체를 복습한다.

### 읽는 순서

**9-1. [src/main/resources/db/migration/](../src/main/resources/db/migration/) — 시간순으로**

> `db/archive/`는 **과거 이력**이고, 실제 적용되는 것은 `db/migration/`입니다(`application.yaml`의 `flyway.locations` 확인). 시간이 없으면 archive는 건너뛰어도 됩니다.

1. `V202606190133__baseline_v1.sql` — 전체 스키마의 출발점. 테이블 목록을 훑으며 세션 1~7에서 본 엔티티와 대응시켜 보십시오.
2. `V202607101205__create_external_settlement_partitions.sql` — 파티셔닝(7-1과 연결).
3. `V202607130001__create_idempotency_records.sql` — 2-7과 연결.
4. `V202607141020__add_correlation_id_to_outbox_events.sql`, `V202607151648__add_locked_at_to_outbox_events.sql` — 세션 3과 연결.
5. `V202608240001__fix_transaction_entry_amount_constraint.sql` — **`chk_amount_calculation` 제약.** 4-2의 `amount = unitPrice × quantity × exchangeRate`를 DB가 직접 강제합니다. 자바 계산과 DB 스케일을 맞추는 `toDbScale()`이 왜 필요했는지 여기서 확인됩니다.
6. `V202608240002__create_ledger_dead_letter_and_settlement_match.sql` — 4-7, 7-8과 연결. `uk_settlement_match_settlement` 유니크 제약을 꼭 확인하십시오.
7. `V202608240003__seed_system_accounts.sql` — `LedgerService`의 `SYSTEM_FEE_ACCOUNT_ID`, `SYSTEM_ACCOUNT_ID`가 여기서 만들어집니다.
8. `V202608240004__outbox_backoff_missing_indexes_currency_backfill.sql`, `V202608250001__migrate_idempotency_keys.sql`

**9-2. [test/.../IntegrationTestSupport.java](../src/test/java/com/github/raonjena99/multi_currency_ledger_service/IntegrationTestSupport.java)**

Testcontainers로 PostgreSQL + Redis + Kafka를 실제로 띄웁니다. 통합 테스트는 전부 이 클래스를 상속합니다.

**9-3. [test/.../architecture/LedgerArchitectureTest.java](../src/test/java/com/github/raonjena99/multi_currency_ledger_service/architecture/LedgerArchitectureTest.java)**

- `modules.verify()` — 모듈 간 순환 참조와 잘못된 의존성을 **테스트로** 막습니다.
- `Documenter` — README의 아키텍처 다이어그램이 여기서 자동 생성됩니다.

**9-4. [test/.../regression/](../src/test/java/com/github/raonjena99/multi_currency_ledger_service/regression/) — 전부**

**이 디렉터리가 이 프로젝트에서 가장 압축적인 학습 자료입니다.** 각 테스트 = 과거에 실제로 터졌던 결함 하나. 테스트 이름만 읽어도 시스템의 취약점 지도가 그려집니다.

| 테스트 | 지키는 불변식 | 관련 세션 |
| :--- | :--- | :--- |
| `MoneyConservationTest` | 반올림이 통화를 만들거나 없애지 않는다 | 1, 2 |
| `LedgerPeriodIntegrityTest` | 거래가 조회되지 않는 월에 기장되지 않는다 | 2 |
| `LatestLedgerSelectionTest` | 최신 월 판별이 `ledger_month` 기준이다 | 5 |
| `LedgerConsistencyTest` | 잔고 합계와 분개 합계가 일치한다 | 2, 4 |
| `ForeignCurrencyLedgerTest` | 외화 거래의 환산이 정확하다 | 4 |
| `RealizedPnlUnitTest` | 실현손익 단위 규약 | 4 |
| `OutboxRelayResilienceTest` | 릴레이 실패가 메시지를 잃지 않는다 | 3 |
| `SettlementMatchIntegrityTest` | 1:1 매칭이 깨지지 않는다 | 7 |
| `AccountAccessControlTest` | 남의 계좌를 만질 수 없다 | 8 |
| `TransactionalProxyGuardTest` | `REQUIRES_NEW`가 프록시를 거친다 | 2, 7 |
| `SchemaGuardTest` | 엔티티와 스키마가 어긋나지 않는다 | 9 |
| `ApiErrorContractTest` | 에러 응답 계약 | 8 |
| `TradeMatrixTest` / `LedgerMatrixTest` | 자산×통화 조합 전수 검증 | 2, 4 |

**9-5. 마지막 — [e2e/TradeToLedgerE2ETest.java](../src/test/java/com/github/raonjena99/multi_currency_ledger_service/e2e/TradeToLedgerE2ETest.java)를 다시 한 번**

세션 4에서 이미 읽었지만, 지금 다시 읽으면 **모든 단계가 이름만으로 이해될 것입니다.** 그렇다면 이 가이드의 목적은 달성된 것입니다.

---

## 마지막 점검 — 이 8개에 답할 수 있으면 완주입니다

1. 매수 요청 하나가 들어와서 `transaction_entries`에 두 행이 생기기까지, 거치는 컴포넌트를 순서대로 나열하고 각 단계의 트랜잭션 경계를 표시해 보십시오.
2. Kafka 브로커가 30분 다운되었다가 복구되면 그동안의 거래는 어떻게 되는가?
3. 같은 계좌에 동시에 매수 요청 5건이 들어오면 어떤 동시성 제어가 작동하는가? (최소 3가지)
4. 12월 31일 23:59에 매수하고 1월 1일 00:01에 매도하면 잔고와 분개는 각각 몇 월에 기록되는가?
5. `verifyDoubleEntry()`가 통과했다고 해서 금액이 맞다는 뜻인가?
6. 시세 API, Redis, Kafka, PG API가 각각 죽었을 때 서비스는 어떻게 되는가? (4개 다)
7. 대사 배치가 발견한 수수료 차액이 고객 잔고에 반영되기까지의 전체 경로는?
8. 이 시스템에서 **at-least-once**를 **effectively-once**로 만드는 장치는 각 단계에 무엇이 있는가?

---

## 부록 — 파일 위치 빠른 참조

| 궁금한 것 | 볼 파일 |
| :--- | :--- |
| 금액이 어떻게 표현되나 | `common/domain/Money.java` |
| 잔고가 어디서 바뀌나 | `account/application/AccountTradeService.java` |
| 잔고가 어디에 저장되나 | `account/domain/MonthlyAccountLedger.java` |
| 분개가 어디서 만들어지나 | `transaction/application/LedgerService.java` |
| 대차평균 검증은 어디에 | `transaction/domain/Transaction.java#verifyDoubleEntry` |
| 메시지가 어떻게 전달되나 | `common/outbox/OutboxRelayWorker.java` |
| 포트폴리오 조회 로직 | `portfolio/application/PortfolioQueryService.java` |
| 시세를 어디서 가져오나 | `common/infrastructure/adapter/MarketDataRouter.java` |
| 대사 매칭 규칙 | `reconciliation/application/rule/` |
| 권한 검사 | `common/security/SecurityConfig.java` + `AccountOwnershipGuard.java` |
| 에러 응답 규약 | `common/exception/GlobalExceptionHandler.java` |
| 모든 튜닝 값 | `src/main/resources/application.yaml` |
