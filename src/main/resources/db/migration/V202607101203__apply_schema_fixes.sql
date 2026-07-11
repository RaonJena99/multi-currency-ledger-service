-- 1. Sequence Renaming & Allocation Size Update (Batch Insert Support)
-- JPA의 allocationSize=50 과 맞추어 충돌을 방지합니다.
ALTER SEQUENCE transaction_entries_id_seq RENAME TO transaction_entry_seq;
ALTER SEQUENCE transaction_entry_seq INCREMENT BY 50;

ALTER SEQUENCE monthly_account_ledgers_id_seq RENAME TO monthly_account_ledger_seq;
ALTER SEQUENCE monthly_account_ledger_seq INCREMENT BY 50;

ALTER SEQUENCE outbox_events_id_seq RENAME TO outbox_event_seq;
ALTER SEQUENCE outbox_event_seq INCREMENT BY 50;

-- 2. ExternalSettlement Amount Precision Fix (정수부 18자리 보장)
ALTER TABLE external_settlement ALTER COLUMN amount TYPE NUMERIC(36, 18);

-- 3. UUID Type Consistency Fix (VARCHAR -> Native UUID 형변환)
ALTER TABLE external_settlement ALTER COLUMN id TYPE uuid USING id::uuid;
ALTER TABLE external_settlement ALTER COLUMN matched_internal_transaction_id TYPE uuid USING matched_internal_transaction_id::uuid;
ALTER TABLE reconciliation_dead_letter ALTER COLUMN external_settlement_id TYPE uuid USING external_settlement_id::uuid;

-- 4. Unique Constraint for Partitioning
-- 파티셔닝된 테이블에서는 파티션 키를 포함해야만 Unique 인덱스 생성이 가능합니다.
CREATE UNIQUE INDEX IF NOT EXISTS uk_external_ref_id_settlement_date 
ON external_settlement(external_reference_id, settlement_date);

-- 5. Missing Indexes (조회 성능 최적화)
CREATE INDEX IF NOT EXISTS idx_outbox_event_processed ON outbox_events(processed, created_at);
CREATE INDEX IF NOT EXISTS idx_transaction_id ON transaction_entries(transaction_id);
CREATE INDEX IF NOT EXISTS idx_account_id ON transaction_entries(account_id);

-- 6. Date Type Fixes (시간 데이터 일관성)
ALTER TABLE outbox_events ALTER COLUMN created_at TYPE timestamp with time zone USING created_at AT TIME ZONE 'UTC';
