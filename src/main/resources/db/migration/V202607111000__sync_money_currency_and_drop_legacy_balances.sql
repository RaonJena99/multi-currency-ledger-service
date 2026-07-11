-- 뷰 및 레거시 테이블 완전 청산
DROP MATERIALIZED VIEW IF EXISTS public.current_portfolio_view CASCADE;
DROP VIEW IF EXISTS public.portfolio_summary_view CASCADE;
DROP TABLE IF EXISTS public.account_balances CASCADE;

-- 2. Money 객체 임베딩 테이블에 currency_code 컬럼 추가

-- monthly_account_ledgers
ALTER TABLE public.monthly_account_ledgers 
    ADD COLUMN balance_currency VARCHAR(10) NOT NULL DEFAULT 'KRW',
    ADD COLUMN average_unit_price_currency VARCHAR(10) NOT NULL DEFAULT 'KRW';

-- transaction_entries
ALTER TABLE public.transaction_entries 
    ADD COLUMN quantity_currency VARCHAR(10) NOT NULL DEFAULT 'KRW',
    ADD COLUMN unit_price_currency VARCHAR(10) NOT NULL DEFAULT 'KRW',
    ADD COLUMN amount_currency VARCHAR(10) NOT NULL DEFAULT 'KRW',
    ADD COLUMN realized_pnl_currency VARCHAR(10) NOT NULL DEFAULT 'KRW';

-- 데이터 정합성 보호를 위한 DEFAULT 제약조건 해제
ALTER TABLE public.monthly_account_ledgers ALTER COLUMN balance_currency DROP DEFAULT;
ALTER TABLE public.monthly_account_ledgers ALTER COLUMN average_unit_price_currency DROP DEFAULT;

ALTER TABLE public.transaction_entries ALTER COLUMN quantity_currency DROP DEFAULT;
ALTER TABLE public.transaction_entries ALTER COLUMN unit_price_currency DROP DEFAULT;
ALTER TABLE public.transaction_entries ALTER COLUMN amount_currency DROP DEFAULT;
ALTER TABLE public.transaction_entries ALTER COLUMN realized_pnl_currency DROP DEFAULT;

-- 3. 외부 정산(External Settlement) 테이블 길이 확장 및 정밀도 동기화
ALTER TABLE public.external_settlement 
    ALTER COLUMN currency_code TYPE VARCHAR(10),
    ALTER COLUMN amount TYPE NUMERIC(36,18);

-- 4. 논리적 버그가 수정된 단일 진실 공급원(SSOT) 뷰(View) 재생성

-- 구체화된 포트폴리오 뷰
CREATE MATERIALIZED VIEW public.current_portfolio_view AS
 SELECT DISTINCT ON (mal.account_id, mal.asset_code) 
    md5(((mal.account_id)::text || (mal.asset_code)::text)) AS id,
    mal.account_id,
    mal.asset_code,
    mal.balance_currency AS balance_currency,
    mal.average_unit_price_currency AS quote_currency,
    mal.balance AS total_quantity,
    mal.average_unit_price AS avg_unit_price,
    COALESCE(md.current_market_price, (0)::numeric) AS current_market_price,
    ((COALESCE(md.current_market_price, (0)::numeric) - mal.average_unit_price) * mal.balance) AS unrealized_pnl,
    mal.ledger_month AS last_updated_month
   FROM (public.monthly_account_ledgers mal
     LEFT JOIN public.market_data md 
     ON ((mal.asset_code)::text = (md.base_asset_code)::text 
     AND (mal.average_unit_price_currency)::text = (md.quote_asset_code)::text))
  ORDER BY mal.account_id, mal.asset_code, mal.ledger_month DESC
  WITH NO DATA;

-- 구체화된 뷰(Materialized View)의 성능을 위한 인덱스 재생성
CREATE UNIQUE INDEX idx_current_portfolio_unique ON public.current_portfolio_view USING btree (account_id, asset_code);

-- 5. BaseEntity 동기화를 위한 누락된 updated_at 컬럼 추가
ALTER TABLE public.reconciliation_dead_letter
    ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL;

-- 데이터 마이그레이션 후 DEFAULT 해제
ALTER TABLE public.reconciliation_dead_letter ALTER COLUMN updated_at DROP DEFAULT;

-- Add updated_at if not exists for all BaseEntity extending tables
ALTER TABLE public.outbox_events ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL;
ALTER TABLE public.outbox_events ALTER COLUMN updated_at DROP DEFAULT;
ALTER TABLE public.monthly_account_ledgers ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL;
ALTER TABLE public.monthly_account_ledgers ALTER COLUMN updated_at DROP DEFAULT;
ALTER TABLE public.external_settlement ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL;
ALTER TABLE public.external_settlement ALTER COLUMN updated_at DROP DEFAULT;
