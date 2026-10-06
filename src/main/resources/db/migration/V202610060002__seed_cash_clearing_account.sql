-- V202610060002__seed_cash_clearing_account.sql
-- 외부 입출금 청산 계정 시딩
--
--   00000000-0000-0000-0000-000000000002  SYSTEM_CASH_CLEARING (입출금 분개의 상대 계정)
--
-- 입금은 고객 현금 차변 / 청산 계정 대변, 출금은 그 반대로 분개합니다.
-- transaction_entries.account_id 에는 accounts(id) 외래키가 걸려 있으므로, 이 행이 없으면
-- 모든 입출금의 원장 기록이 외래키 위반으로 실패해 DLT 로 빠집니다.
--
-- base_currency 는 다른 시스템 계정과 같이 자리표시자입니다. 실제 통화는 각 분개 엔트리가 보유합니다.

INSERT INTO public.accounts (id, owner_name, status, base_currency, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-000000000002', 'SYSTEM_CASH_CLEARING', 'ACTIVE', 'KRW', now(), now())
ON CONFLICT (id) DO NOTHING;
