-- market_data 다통화 지원을 위한 스키마 재설계
DROP TABLE IF EXISTS market_data CASCADE;
CREATE TABLE market_data (
    base_asset_code VARCHAR(20) NOT NULL,
    quote_asset_code VARCHAR(20) NOT NULL,
    current_market_price DECIMAL(36, 18) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (base_asset_code, quote_asset_code)
);

-- 데이터 무결성 제약조건 추가
ALTER TABLE transaction_entries
ADD CONSTRAINT chk_amount_calculation 
CHECK (amount = (quantity * unit_price * exchange_rate));

ALTER TABLE transaction_entries
ADD CONSTRAINT chk_entry_type
CHECK (entry_type IN ('DEBIT', 'CREDIT'));

-- 다통화 정합성이 확보된 포트폴리오 평가 뷰
CREATE OR REPLACE VIEW portfolio_summary_view AS
SELECT 
    ab.account_id,
    ab.asset_code AS base_asset,
    ab.average_unit_price_asset_type AS quote_asset,
    ab.balance AS current_quantity,
    ab.average_unit_price AS cost_basis,
    md.current_market_price,
    (md.current_market_price - ab.average_unit_price) * ab.balance AS unrealized_pnl
FROM account_balances ab
LEFT JOIN market_data md 
    ON ab.asset_code = md.base_asset_code 
    AND ab.average_unit_price_asset_type = md.quote_asset_code;