-- V202610060003__create_ledger_integrity_tables.sql
-- 월차 원장 잔고와 분개 누계의 정합성 점검 결과를 남깁니다.
--
-- 회차(run)마다 한 행을 남겨, 불일치가 없던 회차도 "점검했고 문제없었다"는 기록이 됩니다.
-- 불일치는 계좌·자산 단위로 회차에 매달아 저장합니다.

CREATE TABLE public.ledger_integrity_runs (
    id                      uuid PRIMARY KEY,
    checked_at              timestamp with time zone NOT NULL,
    checked_count           integer NOT NULL,
    skipped_count           integer NOT NULL,
    mismatch_count          integer NOT NULL,
    trial_balance_balanced  boolean NOT NULL
);

CREATE INDEX idx_ledger_integrity_runs_checked_at
    ON public.ledger_integrity_runs (checked_at DESC);

CREATE TABLE public.ledger_integrity_mismatches (
    id                  bigserial PRIMARY KEY,
    run_id              uuid NOT NULL REFERENCES public.ledger_integrity_runs (id),
    account_id          uuid NOT NULL,
    asset_code          varchar(20) NOT NULL,
    ledger_balance      numeric(36, 18) NOT NULL,
    journal_balance     numeric(36, 18) NOT NULL,
    difference          numeric(36, 18) NOT NULL,
    outbox_dead_letter  boolean NOT NULL
);

CREATE INDEX idx_ledger_integrity_mismatches_run_id
    ON public.ledger_integrity_mismatches (run_id);
