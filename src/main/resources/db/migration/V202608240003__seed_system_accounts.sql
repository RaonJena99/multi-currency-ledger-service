-- 시스템 계정 시딩
--
-- transaction_entries.account_id 에는 accounts(id) 로의 외래키가 걸려 있습니다.
-- 그런데 원장 분개는 다음 두 시스템 계정을 참조합니다.
--
--   00000000-0000-0000-0000-000000000000  SYSTEM_FX_GAIN / SYSTEM_FX_LOSS (반올림 잔차 플러그)
--   00000000-0000-0000-0000-000000000001  FEE_GAIN / FEE_LOSS / 수수료 차감 귀속
--
-- 이 계정 행이 없으면 플러그나 수수료 분개가 필요한 순간 INSERT 가 외래키 위반으로 실패합니다.
-- 결제 통화와 기준 통화가 다른 <b>모든</b> 거래는 반올림 잔차 플러그를 필요로 하므로,
-- 사실상 외화 거래 전체가 원장 기록에 실패하고 DLT 로 빠집니다.
--
-- base_currency 는 자리표시자입니다. 시스템 계정은 여러 통화를 동시에 담으며,
-- 실제 통화는 각 분개 엔트리의 amount_currency 가 보유합니다.

INSERT INTO public.accounts (id, owner_name, status, base_currency, created_at, updated_at)
VALUES
    ('00000000-0000-0000-0000-000000000000', 'SYSTEM_FX', 'ACTIVE', 'KRW', now(), now()),
    ('00000000-0000-0000-0000-000000000001', 'SYSTEM_FEE', 'ACTIVE', 'KRW', now(), now())
ON CONFLICT (id) DO NOTHING;
