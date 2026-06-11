-- 기존 account_balances 테이블 대체
CREATE TABLE monthly_account_ledgers (
    id BIGSERIAL PRIMARY KEY,
    account_id UUID NOT NULL,
    asset_code VARCHAR(20) NOT NULL,
    ledger_month VARCHAR(7) NOT NULL, 
    
    balance DECIMAL(36, 18) NOT NULL,
    asset_type VARCHAR(20) NOT NULL,
    
    average_unit_price DECIMAL(36, 18) NOT NULL,
    average_unit_price_asset_type VARCHAR(20) NOT NULL,
    
    carried_forward BOOLEAN NOT NULL DEFAULT FALSE,
    
    version BIGINT DEFAULT 0,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    
    CONSTRAINT uk_account_asset_month UNIQUE (account_id, asset_code, ledger_month)
);

CREATE INDEX idx_monthly_ledger_search ON monthly_account_ledgers (account_id, asset_code, ledger_month DESC);