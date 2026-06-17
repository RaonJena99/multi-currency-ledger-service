CREATE TABLE reconciliation_dead_letter (
    id BIGSERIAL NOT NULL,
    external_settlement_id VARCHAR(36) NOT NULL,
    failure_reason VARCHAR(30) NOT NULL,
    error_message VARCHAR(500) NOT NULL,
    is_resolved BOOLEAN NOT NULL DEFAULT FALSE,
    resolved_at TIMESTAMP WITH TIME ZONE NULL,
    handler_enrichment_payload JSONB NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_reconciliation_dead_letter PRIMARY KEY (id)
);

CREATE INDEX idx_dlq_unresolved_tracker 
ON reconciliation_dead_letter (failure_reason, created_at DESC) 
WHERE is_resolved = FALSE;

CREATE INDEX idx_dlq_external_settlement_id 
ON reconciliation_dead_letter (external_settlement_id);