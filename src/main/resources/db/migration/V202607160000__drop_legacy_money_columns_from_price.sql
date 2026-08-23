-- 1. Drop the materialized view that depends on these columns
DROP MATERIALIZED VIEW IF EXISTS public.current_portfolio_view CASCADE;

-- 2. Drop the legacy money columns from transaction_entries (unit_price is now just a BigDecimal)
ALTER TABLE public.transaction_entries
    DROP COLUMN IF EXISTS unit_price_asset_type,
    DROP COLUMN IF EXISTS unit_price_currency;

-- 3. Drop the legacy money columns from monthly_account_ledgers (average_unit_price is now just a BigDecimal)
ALTER TABLE public.monthly_account_ledgers
    DROP COLUMN IF EXISTS average_unit_price_asset_type;

