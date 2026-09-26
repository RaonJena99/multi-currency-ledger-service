<div align="center">
  <h1>Multi-Currency Ledger Service</h1>
  <p><b>법정화폐·암호화폐를 함께 다루는 복식부기 원장 서비스</b></p>

  <p>
    <img src="https://img.shields.io/badge/Java%2021-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java" />
    <img src="https://img.shields.io/badge/Spring%20Boot%204-6DB33F?style=for-the-badge&logo=springboot&logoColor=white" alt="Spring Boot" />
    <img src="https://img.shields.io/badge/PostgreSQL-336791?style=for-the-badge&logo=postgresql&logoColor=white" alt="PostgreSQL" />
    <img src="https://img.shields.io/badge/Apache%20Kafka-231F20?style=for-the-badge&logo=apachekafka&logoColor=white" alt="Kafka" />
    <img src="https://img.shields.io/badge/Redis-DC382D?style=for-the-badge&logo=redis&logoColor=white" alt="Redis" />
  </p>
</div>

계좌에서 자산을 사고팔면 **잔고는 즉시 바뀌고, 복식부기 분개는 Kafka를 거쳐 비동기로 기록**됩니다.
외부 시세로 포트폴리오를 평가하고, PG 정산 데이터와 내부 거래를 맞춰 보는 대사 배치를 갖추고 있습니다.

---

## 주요 기능

| 기능 | 내용 |
| :--- | :--- |
| **매수·매도** | 월차 원장의 잔고와 평균 단가 갱신. 멱등성 키로 중복 요청 차단, 시장가 대비 단가 편차 검증 |
| **복식부기 분개** | 거래 이벤트를 Kafka로 받아 차변·대변 분개 기록. 매도 시 실현 손익 포함, 대차 불일치 시 저장 거부 |
| **포트폴리오 조회** | 보유 자산을 실시간 시세로 평가해 미실현 손익 계산. Redis 캐시, 시세 장애 시 지연 데이터로 표시 |
| **정산 대사** | 월 1회 배치로 PG 정산과 내부 거래를 시간·금액·텍스트 규칙으로 매칭. 불일치 건은 DLQ로 격리 |

## 핵심 설계

- **`Money` 값 객체**: `BigDecimal` 기반으로 자산별 소수 자릿수를 적용하고 통화가 다른 금액끼리의 연산을 막습니다. 고객이 내는 금액은 올림, 받는 금액은 내림합니다.
- **동시성 제어**: 원장 갱신은 낙관적 락(`@Version`)과 재시도로, 아웃박스 폴링은 `FOR UPDATE SKIP LOCKED`로 처리합니다.
- **Transactional Outbox**: 잔고 변경과 이벤트 저장을 한 트랜잭션에서 커밋하고, 릴레이가 5초마다 Kafka로 발행합니다.
  - 전달 보장은 **at-least-once**이며, 중복은 컨슈머가 거래 ID로 걸러냅니다.
  - 발행 실패 시 30초~10분 지수 백오프로 최대 10회 재시도한 뒤 데드레터로 격리합니다.
- **외부 연동 복원력**: 시세·PG API에 Resilience4j 서킷 브레이커와 재시도를 적용합니다. 시세 공급자가 장애이면 캐시로 대응하되, **5분이 지난 시세로는 거래하지 않습니다**(503).
- **모듈 분리**: Spring Modulith로 `account`, `transaction`, `portfolio`, `reconciliation` 모듈의 경계를 테스트로 검증합니다.
- **관측성**: Correlation ID를 HTTP 요청부터 Kafka 컨슈머 로그까지 전달하고, 로그는 JSON(Logstash 인코더)으로 남깁니다. 지표는 Prometheus로 노출합니다(외부 API 응답 시간, 폴백·데드레터 건수, 통화별 보유 총액 등).

---

## 빠른 시작

```bash
# 1. 인프라 실행 (PostgreSQL, Redis, Kafka, Prometheus, Grafana)
docker compose up -d

# 2. 애플리케이션 실행 (local 프로파일은 외부 시세 API 대신 더미 시세 사용)
./gradlew bootRun --args='--spring.profiles.active=local'

# 테스트 (Testcontainers 사용, Docker 필요)
./gradlew test
```

DB 스키마는 기동 시 Flyway가 자동으로 적용합니다.

---

## 배포

```text
PR / main 푸시   ─▶ CI: 전체 테스트 + 이미지 빌드 후 운영 스택으로 기동 확인(스모크 테스트)
main 머지        ─▶ release-please 가 릴리스 PR(다음 버전, CHANGELOG)을 갱신
릴리스 PR 머지   ─▶ 테스트 → 이미지 빌드 → 스모크 테스트 → GHCR 발행
```

- 버전은 커밋 메시지(Conventional Commits)로 정해집니다. `fix:`는 패치, `feat:`는 마이너 버전을 올립니다.
- 이미지는 `ghcr.io/raonjena99/multi-currency-ledger-service`에 `1.2.3`, `1.2`, `latest`, `sha-xxxxxxx` 태그로 발행됩니다(linux/amd64).
- 스모크 테스트를 통과한 이미지만 발행됩니다. 로컬에서도 같은 검사를 돌릴 수 있습니다.
  ```bash
  docker build -t ledger:local . && deploy/smoke-test.sh ledger:local
  ```

서버에서는 `deploy/`의 운영용 스택(앱, PostgreSQL, Redis, Kafka)으로 실행합니다.

```bash
cd deploy
cp .env.example .env        # DB_PASSWORD, GATEWAY_SHARED_SECRET 은 필수
docker compose pull && docker compose up -d
```

- 버전을 올릴 때는 `.env`의 `APP_VERSION`을 바꾸고 위 명령을 다시 실행합니다.
- GHCR 패키지가 비공개라면 서버에서 먼저 `docker login ghcr.io`로 로그인해야 합니다.
- 운영용 스택은 애플리케이션 포트만 호스트에 노출하고, 모든 서비스에 재시작 정책(`unless-stopped`)을 둡니다.

---

## API

| 메서드 | 경로 | 권한 |
| :--- | :--- | :--- |
| `POST` | `/api/v1/accounts/{accountId}/trades/buy` | 인증 + 계좌 소유자 |
| `POST` | `/api/v1/accounts/{accountId}/trades/sell` | 인증 + 계좌 소유자 |
| `GET` | `/api/v1/portfolios/{accountId}` | 인증 + 계좌 소유자 |
| `GET` | `/api/v1/admin/outbox/dead-letters` | `ROLE_ADMIN` |
| `POST` | `/api/v1/admin/outbox/dead-letters/{eventId}/requeue`, `/requeue-all` | `ROLE_ADMIN` |
| `POST` | `/api/v1/admin/reconciliations/dead-letters/{deadLetterId}/resolve` | `ROLE_ADMIN` |
| `GET` | `/actuator/health`, `/info`, `/prometheus` | 공개 (나머지 `/actuator/**`는 `ROLE_ADMIN`) |

관리자는 계좌 소유권 검사를 건너뜁니다.

### 인증 방식

이 서비스는 토큰을 직접 검증하지 않고, **API 게이트웨이가 검증 후 넣어 준 헤더**를 신뢰합니다.

```text
X-Auth-Subject     주체 식별자 (필수)
X-Auth-Account-Id  소유 계좌 UUID
X-Auth-Roles       쉼표로 구분한 역할. ADMIN 또는 ROLE_ADMIN 이면 관리자
X-Gateway-Secret   ledger.security.gateway-secret 을 설정한 경우 필수
```

> [!WARNING]
> `GATEWAY_SHARED_SECRET`을 설정하지 않으면 누구나 위 헤더를 직접 보내 다른 사람이나 관리자로 행세할 수 있습니다.
> 외부에 노출하는 환경에서는 반드시 설정하거나, 게이트웨이에서 클라이언트가 보낸 `X-Auth-*` 헤더를 제거하십시오.
> JWT를 직접 검증하려면 `PrincipalResolver`를 구현한 빈을 등록하면 기본 구현체를 대신합니다.

---

## 시세 공급자

`MarketDataRouter`가 자산 코드를 보고 공급자를 고릅니다.

| 자산군 | 공급자 | 비고 |
| :--- | :--- | :--- |
| 법정화폐 | [fxratesapi.com](https://fxratesapi.com) | API 키 없이 사용 가능 |
| 암호화폐 | [CoinGecko](https://www.coingecko.com/en/api) | 여러 코인을 한 번의 호출로 조회 |
| 주식 등 그 외 | 없음 | `422 UNSUPPORTED_ASSET`로 거부 |

- 1보다 훨씬 작은 환율(예: `KRW→BTC`)은 무료 API가 유효숫자를 잘라 버립니다. 그래서 항상 값이 큰 방향으로 조회하고 역수를 직접 계산합니다.
- 지원 암호화폐는 `ledger.external.crypto.symbol-ids`(심볼 → CoinGecko ID)에 등록합니다.

| 환경 변수 | 기본값 |
| :--- | :--- |
| `EXCHANGE_RATE_API_URL` / `EXCHANGE_RATE_API_KEY` | `https://api.fxratesapi.com` / 없음(선택) |
| `CRYPTO_PRICE_API_URL` / `CRYPTO_PRICE_API_KEY` | `https://api.coingecko.com` / 없음(선택) |
| `PG_API_URL` | 없음 |
| `GATEWAY_SHARED_SECRET` | 없음 |

> [!NOTE]
> **PG 정산 연동은 실제로 동작하지 않습니다.** 개인이 접속할 수 있는 PG 정산 API가 없어 `PG_API_URL` 기본값이 없고,
> 정산 적재 서비스(`SettlementIngestionService`)를 호출하는 스케줄러나 API도 아직 없습니다. 따라서 대사 배치는 실행돼도 처리할 데이터가 없습니다.

---

## 아키텍처

<details>
<summary><b>모듈 및 컴포넌트 구성도</b></summary>

#### System Components
![System Components](docs/architecture/modulith/components.svg)

#### Bounded Context
| Account (계좌) | Transaction (원장) |
| :--- | :--- |
| ![Account](docs/architecture/modulith/module-account.svg) | ![Transaction](docs/architecture/modulith/module-transaction.svg) |
| **Portfolio (자산)** | **Reconciliation (대사)** |
| ![Portfolio](docs/architecture/modulith/module-portfolio.svg) | ![Reconciliation](docs/architecture/modulith/module-reconciliation.svg) |

</details>

```text
src/main/java/.../
├── account/          # 매수·매도, 월차 원장, 멱등성
├── transaction/      # 복식부기 분개, Kafka 컨슈머, 원장 DLT
├── portfolio/        # 포트폴리오 평가, Redis 캐시
├── reconciliation/   # 정산 적재, 대사 배치(Spring Batch), 매칭 규칙
└── common/           # Money, 시세 어댑터, 아웃박스, 보안, 설정
src/main/resources/db/migration/   # Flyway 마이그레이션
deploy/                            # 운영용 docker compose, 이미지 스모크 테스트
```

코드를 처음 읽는다면 [코드 읽기 가이드](docs/CODE_READING_GUIDE.md)부터 보십시오.
