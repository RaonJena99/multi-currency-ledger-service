-- Add correlation_id to outbox_events to support distributed tracing
ALTER TABLE public.outbox_events ADD COLUMN IF NOT EXISTS correlation_id VARCHAR(100);
