-- AccountBalance: 자산 타입 및 평균 단가의 기준 통화 타입 추가
ALTER TABLE account_balances 
    ADD COLUMN asset_type VARCHAR(20) NOT NULL DEFAULT 'CRYPTO';
ALTER TABLE account_balances 
    ADD COLUMN average_unit_price_asset_type VARCHAR(20) NOT NULL DEFAULT 'FIAT';

-- TransactionEntry: 모든 금액 필드에 대한 명시적 통화 타입 추가
ALTER TABLE transaction_entries 
    RENAME COLUMN asset_type TO quantity_asset_type;
    
ALTER TABLE transaction_entries 
    ADD COLUMN unit_price_asset_type VARCHAR(20) NOT NULL DEFAULT 'FIAT',
    ADD COLUMN amount_asset_type VARCHAR(20) NOT NULL DEFAULT 'FIAT',
    ADD COLUMN realized_pnl_asset_type VARCHAR(20) NOT NULL DEFAULT 'FIAT';