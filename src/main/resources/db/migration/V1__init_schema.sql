CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    owner_name VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE account_balances (
    id BIGSERIAL PRIMARY KEY,
    account_id UUID NOT NULL,
    asset_code VARCHAR(20) NOT NULL,
    balance DECIMAL(36, 18) NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    FOREIGN KEY (account_id) REFERENCES accounts(id),
    UNIQUE (account_id, asset_code)
);

CREATE TABLE transactions (
    id UUID PRIMARY KEY,
    transaction_type VARCHAR(30) NOT NULL,
    description VARCHAR(255) NOT NULL,
    transacted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE transaction_entries (
    id BIGSERIAL PRIMARY KEY,
    transaction_id UUID NOT NULL,
    account_id UUID NOT NULL,
    asset_code VARCHAR(20) NOT NULL,
    asset_type VARCHAR(20) NOT NULL, 
    quantity DECIMAL(36, 18) NOT NULL,
    unit_price DECIMAL(36, 18) NOT NULL,
    exchange_rate DECIMAL(19, 6) DEFAULT 1.0,
    amount DECIMAL(36, 18) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    FOREIGN KEY (transaction_id) REFERENCES transactions(id),
    FOREIGN KEY (account_id) REFERENCES accounts(id)
);

CREATE INDEX idx_balance_account ON account_balances(account_id);
CREATE INDEX idx_entry_account_asset ON transaction_entries(account_id, asset_code);
CREATE INDEX idx_entry_transaction_id ON transaction_entries(transaction_id);

CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER update_accounts_updated_at
    BEFORE UPDATE ON accounts
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_account_balances_updated_at
    BEFORE UPDATE ON account_balances
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();