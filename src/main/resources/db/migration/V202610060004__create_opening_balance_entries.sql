-- V202610060004__create_opening_balance_entries.sql
-- 기초 잔고 분개 생성
--
-- 입출금 API(#61) 이전에는 잔고를 DB 에 직접 넣어야 했으므로, 그 잔고에는 대응하는 분개가 없습니다.
-- 그대로 두면 잔고와 분개 누계를 비교하는 정합성 점검이 모든 계좌를 불일치로 보고합니다.
-- 회계에서 이월 잔고를 다루는 방식대로, 차이만큼 기초 잔고(OPENING_BALANCE) 분개를 한 번 만듭니다.
--
--   고객 계좌            차변 (잔고가 분개 누계보다 크면) / 대변 (작으면)
--   SYSTEM_OPENING_BALANCE  반대쪽
--
-- 금액(amount)은 월차 원장의 평균 단가로 환산한 기준 통화 값입니다.
--
-- 다음 계좌는 건너뜁니다. 기초 잔고로 덮으면 실제로 빠진 분개를 가리게 됩니다.
--   - 발행되지 않은 아웃박스 이벤트가 있는 계좌 (처리 중이거나 데드레터)
--   - 미해결 원장 데드레터가 있는 계좌
-- 이 계좌들은 원장 DLT 재처리·아웃박스 재발행으로 복구한 뒤 정합성 점검으로 확인합니다.
--
-- 차이가 0 인 쌍은 만들지 않으므로 다시 실행해도 분개가 늘지 않습니다.

INSERT INTO public.accounts (id, owner_name, status, base_currency, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-000000000003', 'SYSTEM_OPENING_BALANCE', 'ACTIVE', 'KRW', now(), now())
ON CONFLICT (id) DO NOTHING;

WITH latest AS (
    -- 이전 월 행은 이월된 사본이므로 최신 월 행 하나만 쓴다.
    SELECT DISTINCT ON (l.account_id, l.asset_code)
           l.account_id, l.asset_code, l.asset_type, l.balance_currency, l.balance,
           l.average_unit_price, l.average_unit_price_currency AS base_currency
    FROM public.monthly_account_ledgers l
    ORDER BY l.account_id, l.asset_code, l.ledger_month DESC
), journal AS (
    SELECT account_id, asset_code,
           SUM(CASE WHEN entry_type = 'DEBIT' THEN quantity ELSE -quantity END) AS net
    FROM public.transaction_entries
    GROUP BY account_id, asset_code
), gaps AS (
    SELECT gen_random_uuid() AS transaction_id,
           l.account_id, l.asset_code, l.asset_type, l.balance_currency, l.base_currency, l.average_unit_price,
           l.balance - COALESCE(j.net, 0) AS gap
    FROM latest l
    LEFT JOIN journal j ON j.account_id = l.account_id AND j.asset_code = l.asset_code
    WHERE l.balance <> COALESCE(j.net, 0)
      AND l.account_id NOT IN ('00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000001',
                               '00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000003')
      AND NOT EXISTS (SELECT 1 FROM public.outbox_events o
                      WHERE o.aggregate_id = l.account_id::text AND o.processed = false)
      AND NOT EXISTS (SELECT 1 FROM public.ledger_dead_letters d
                      WHERE d.is_resolved = false AND d.payload LIKE '%' || l.account_id::text || '%')
), new_transactions AS (
    INSERT INTO public.transactions (id, transaction_type, description, transacted_at)
    SELECT transaction_id, 'OPENING_BALANCE', 'Opening balance ' || asset_code || ' (V202610060004)', now()
    FROM gaps
    RETURNING id
)
INSERT INTO public.transaction_entries
    (transaction_id, account_id, entry_type, asset_code, quantity_asset_type, quantity, quantity_currency,
     unit_price, exchange_rate, amount, amount_asset_type, amount_currency,
     realized_pnl, realized_pnl_asset_type, realized_pnl_currency)
SELECT g.transaction_id,
       CASE WHEN side.is_customer THEN g.account_id
            ELSE '00000000-0000-0000-0000-000000000003'::uuid END,
       CASE WHEN (g.gap > 0) = side.is_customer THEN 'DEBIT' ELSE 'CREDIT' END,
       g.asset_code, g.asset_type, abs(g.gap), g.balance_currency,
       g.average_unit_price, 1, round(abs(g.gap) * g.average_unit_price, 2), 'FIAT', g.base_currency,
       0, 'FIAT', g.base_currency
FROM gaps g
CROSS JOIN (VALUES (true), (false)) AS side (is_customer);
