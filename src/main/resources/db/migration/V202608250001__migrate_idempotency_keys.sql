-- V202608250001__migrate_idempotency_keys.sql
-- 완료된 거래(trade_id 가 존재하는)의 기존 멱등성 키를
-- 스코프 형태(accountId:operation:idempotencyKey)로 마이그레이션합니다.
-- 진행 중인(in-flight) 거래는 타임아웃/정리 워커에 의해 만료되므로 무시합니다.

WITH trade_info AS (
    SELECT t.id, t.transaction_type, MIN(te.account_id::text)::uuid as account_id
    FROM public.transactions t
    JOIN public.transaction_entries te ON t.id = te.transaction_id
    GROUP BY t.id, t.transaction_type
)
UPDATE public.idempotency_records ir
SET idempotency_key = ti.account_id || ':' || ti.transaction_type || ':' || ir.idempotency_key
FROM trade_info ti
WHERE ir.trade_id = ti.id
  AND ir.idempotency_key NOT LIKE '%:%:%';
