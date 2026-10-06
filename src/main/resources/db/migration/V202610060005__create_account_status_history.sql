-- V202610060005__create_account_status_history.sql
-- 계좌 상태 변경(정지·해제·해지) 이력
--
-- 누가, 언제, 왜 바꿨는지를 남깁니다. 정지 사유를 계좌별로 바로 조회할 수 있어야 하므로
-- 로그가 아니라 테이블에 둡니다. 상태 변경과 같은 트랜잭션에서 기록되어 누락되지 않습니다.

CREATE TABLE public.account_status_history (
    id           bigserial PRIMARY KEY,
    account_id   uuid NOT NULL REFERENCES public.accounts (id),
    from_status  varchar(20) NOT NULL,
    to_status    varchar(20) NOT NULL,
    reason       varchar(500),
    changed_by   varchar(255) NOT NULL,
    changed_at   timestamp with time zone NOT NULL
);

CREATE INDEX idx_account_status_history_account
    ON public.account_status_history (account_id, changed_at);
