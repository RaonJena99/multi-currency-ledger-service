DROP MATERIALIZED VIEW IF EXISTS current_portfolio_view;

CREATE MATERIALIZED VIEW current_portfolio_view AS
SELECT DISTINCT ON (mal.account_id, mal.asset_code)
    -- 가상의 식별자 생성
    MD5(mal.account_id::text || mal.asset_code) AS id,
    mal.account_id,
    mal.asset_code,
    mal.balance AS total_quantity,
    mal.average_unit_price AS avg_unit_price,
    COALESCE(md.current_market_price, 0) AS current_market_price,
    
    -- 미실현 손익 = (현재 시장가 - 평균 단가) * 보유 수량
    (COALESCE(md.current_market_price, 0) - mal.average_unit_price) * mal.balance AS unrealized_pnl,
    
    mal.ledger_month AS last_updated_month
FROM 
    monthly_account_ledgers mal
LEFT JOIN 
    market_data md 
    ON mal.asset_code = md.base_asset_code 
    AND mal.average_unit_price_asset_type = md.quote_asset_code
ORDER BY 
    mal.account_id, mal.asset_code, mal.ledger_month DESC;

CREATE UNIQUE INDEX idx_current_portfolio_unique ON current_portfolio_view (account_id, asset_code);