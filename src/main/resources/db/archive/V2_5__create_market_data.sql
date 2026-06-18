CREATE TABLE market_data (
    asset_code VARCHAR(20) PRIMARY KEY,
    current_market_price DECIMAL(36, 18) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
