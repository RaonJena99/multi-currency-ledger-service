CREATE TABLE external_settlement (
    id VARCHAR(36) NOT NULL,
    external_reference_id VARCHAR(100) NOT NULL,
    institution_code VARCHAR(20) NOT NULL,
    settlement_date TIMESTAMP WITH TIME ZONE NOT NULL,
    description VARCHAR(255) NOT NULL,
    amount NUMERIC(27, 18) NOT NULL,
    asset_type VARCHAR(20) NOT NULL,
    currency_code VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    matched_internal_transaction_id VARCHAR(36) NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT pk_external_settlement PRIMARY KEY (id, settlement_date)
) PARTITION BY RANGE (settlement_date);

CREATE UNIQUE INDEX uq_institution_external_ref 
ON external_settlement (institution_code, external_reference_id, settlement_date);

CREATE INDEX idx_settlement_date_status 
ON external_settlement (settlement_date, status);