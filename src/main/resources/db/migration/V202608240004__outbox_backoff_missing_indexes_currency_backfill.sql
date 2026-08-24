-- 1. 아웃박스 재시도 백오프 컬럼
--
-- 백오프 없이 폴링 주기(5초)마다 즉시 재시도하면 브로커가 몇 분만 다운되어도
-- 재시도 예산이 소진되어 그 사이의 모든 이벤트가 데드레터로 빠진다.
ALTER TABLE public.outbox_events
    ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMP WITH TIME ZONE;

-- 1-2. 멱등성 레코드에 완료된 거래 ID 기록
--
-- 이 컬럼이 없으면 타임아웃 후 재시도하는 클라이언트가 자신의 성공한 거래 ID 를 되찾을
-- 방법이 없어, 멱등 재전송이 항상 409 로만 끝난다. NULL = 아직 처리 중.
ALTER TABLE public.idempotency_records
    ADD COLUMN IF NOT EXISTS trade_id uuid;

-- 2. 대사 후보 조회 핫 패스 인덱스
--
-- InternalTransactionQueryDao 가 transacted_at 범위로 일자별 후보를 적재한다.
-- 인덱스가 없으면 매 배치가 transactions 전체를 순차 스캔하며, 비용이 거래량에 비례해 영구 증가한다.
CREATE INDEX IF NOT EXISTS idx_transactions_transacted_at
    ON public.transactions (transacted_at);

-- 같은 쿼리가 external_settlement.matched_internal_transaction_id 로 LEFT JOIN 한다.
-- 파티션 테이블이므로 부모에 생성하면 전체 파티션에 전파된다.
CREATE INDEX IF NOT EXISTS idx_settlement_matched_internal_txn
    ON public.external_settlement (matched_internal_transaction_id);

-- 3. V202607111000 마이그레이션의 통화 라벨 백필 오류 보정
--
-- 당시 통화 컬럼들이 DEFAULT 'KRW' 로 추가되어, 마이그레이션 시점에 존재하던
-- 비 KRW 자산(BTC, USD 등) 행이 전부 'KRW' 로 잘못 스탬프되었다.
-- 도메인 계약: balance_currency = asset_code, quantity_currency = asset_code,
-- average_unit_price_currency / amount_currency / realized_pnl_currency = 계좌의 기준 통화.
-- 아래 UPDATE 는 계약과 어긋난 행만 고치므로 정상 데이터에는 no-op 이다.
UPDATE public.monthly_account_ledgers
SET balance_currency = asset_code
WHERE balance_currency <> asset_code;

UPDATE public.monthly_account_ledgers mal
SET average_unit_price_currency = a.base_currency
FROM public.accounts a
WHERE mal.account_id = a.id
  AND mal.average_unit_price_currency <> a.base_currency;

UPDATE public.transaction_entries
SET quantity_currency = asset_code
WHERE quantity_currency <> asset_code;
