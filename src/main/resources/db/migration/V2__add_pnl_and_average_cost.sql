-- 평균 매입 단가 추가
ALTER TABLE account_balances 
ADD COLUMN average_unit_price DECIMAL(36, 18) NOT NULL DEFAULT 0;

-- 차대변 구분 및 실현 손익 추가
ALTER TABLE transaction_entries 
ADD COLUMN entry_type VARCHAR(10) NOT NULL,
ADD COLUMN realized_pnl DECIMAL(36, 18) DEFAULT 0;
