-- 미실현 손익 처리
CREATE OR REPLACE VIEW portfolio_summary_view AS
SELECT 
    ab.account_id,
    ab.asset_code,
    ab.balance AS current_quantity,
    ab.average_unit_price AS cost_basis,
    COALESCE(md.current_market_price, ab.average_unit_price) AS current_market_price,
    (COALESCE(md.current_market_price, ab.average_unit_price) - ab.average_unit_price) * ab.balance AS unrealized_pnl
FROM account_balances ab
LEFT JOIN market_data md ON ab.asset_code = md.asset_code;