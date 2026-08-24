-- 1) 원장 기록 완전 실패(DLT) 격리 테이블
--
-- 잔고 변경은 커밋되었는데 복식부기 기록이 영구 실패하면 잔고와 원장이 영구히 벌어집니다.
-- 기존 LedgerDltConsumer 는 로그만 남겨 추적할 방법이 없었으므로, 실패 건을 조회 가능한
-- 형태로 남겨 운영자가 보상 처리를 할 수 있게 합니다.
CREATE TABLE public.ledger_dead_letters (
    id                  bigserial PRIMARY KEY,
    original_topic      varchar(255) NOT NULL,
    error_message       varchar(2000),
    payload             text NOT NULL,
    correlation_id      varchar(100),
    is_resolved         boolean NOT NULL DEFAULT false,
    resolved_at         timestamp with time zone,
    created_at          timestamp with time zone NOT NULL,
    updated_at          timestamp with time zone NOT NULL
);

CREATE INDEX idx_ledger_dlq_unresolved
    ON public.ledger_dead_letters (created_at DESC)
    WHERE (is_resolved = false);

-- 2) 정산 ↔ 내부 거래 매칭의 1:1 제약 전용 테이블
--
-- external_settlement 는 settlement_date 로 파티션된 테이블이라 파티션 키를 포함하지 않는
-- 전역 유니크 제약을 만들 수 없습니다. 그래서 엔티티의 unique = true 선언이 DB 에 반영되지
-- 않았고, 같은 내부 거래가 서로 다른 두 정산에 매칭되는 것을 막을 방법이 없었습니다.
--
-- 매칭 관계를 비파티션 테이블로 분리해 DB 수준에서 1:1 을 강제합니다.
CREATE TABLE public.settlement_match (
    internal_transaction_id uuid PRIMARY KEY,
    external_settlement_id  uuid NOT NULL,
    settlement_date         timestamp with time zone NOT NULL,
    matched_at              timestamp with time zone NOT NULL,
    created_at              timestamp with time zone NOT NULL,
    updated_at              timestamp with time zone NOT NULL,
    CONSTRAINT uk_settlement_match_settlement
        UNIQUE (external_settlement_id, settlement_date)
);

CREATE INDEX idx_settlement_match_settlement_id
    ON public.settlement_match (external_settlement_id);

-- 3) 멱등성 키 정리 작업이 풀 스캔하지 않도록 인덱스 추가
--
-- IdempotencyRecord 엔티티가 @Index(columnList = "createdAt") 를 선언하지만 마이그레이션에
-- 없었고 ddl-auto: validate 는 인덱스를 검증하지 않아 실제로는 PK 인덱스만 존재했습니다.
CREATE INDEX IF NOT EXISTS idx_idempotency_created_at
    ON public.idempotency_records (created_at);
