ALTER TABLE account_balances 
ADD CONSTRAINT uk_account_asset UNIQUE (account_id, asset_code);