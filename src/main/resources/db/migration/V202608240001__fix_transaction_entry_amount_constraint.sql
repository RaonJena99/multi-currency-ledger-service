-- 기존 chk_amount_calculation 은 amount 가 quantity * unit_price * exchange_rate 와
-- "정확히" 같기를 요구했습니다. 그러나 도메인(TransactionEntry)은 amount 를 Money 로 감싸
-- 기준 통화 스케일로 정규화하므로, 반올림이 발생하는 모든 거래에서 제약이 깨졌습니다.
-- KRW/JPY 처럼 ISO 4217 스케일이 0 인 통화에서는 소수점이 있는 값이면 무조건 깨집니다.
--
-- 두 번째 경로도 있었습니다. exchange_rate 가 numeric(19,6) 이라 18자리 환율이 6자리로
-- 절삭되는데 amount 는 절삭되지 않은 환율로 계산되므로 역시 불일치가 발생했습니다.
--
-- 이 마이그레이션은 두 원인을 각각 제거합니다.

-- 1) 환율 정밀도를 금액과 동일하게 맞춰 절삭에서 오는 불일치를 없앱니다.
ALTER TABLE public.transaction_entries
    ALTER COLUMN exchange_rate TYPE numeric(36, 18);

-- 2) "정확 일치" 대신 "통화 최소 단위 미만의 반올림 오차만 허용"으로 교체합니다.
--    스케일이 0 인 통화의 최대 반올림 오차가 0.5 이므로 1 미만이면 모든 통화를 포괄하며,
--    자리수를 잘못 계산한 수준의 실제 버그는 여전히 제약에 걸립니다.
ALTER TABLE public.transaction_entries
    DROP CONSTRAINT IF EXISTS chk_amount_calculation;

ALTER TABLE public.transaction_entries
    ADD CONSTRAINT chk_amount_rounding_bounded
    CHECK (abs(amount - (quantity * unit_price * exchange_rate)) < 1);
