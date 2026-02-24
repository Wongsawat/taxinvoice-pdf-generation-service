-- Replace the separate status + created_at indexes with a single compound index
-- optimized for the common polling query: WHERE status='PENDING' ORDER BY created_at ASC.
-- This eliminates the need for a separate sort step after the index scan.

DROP INDEX IF EXISTS idx_outbox_status;
DROP INDEX IF EXISTS idx_outbox_created;
DROP INDEX IF EXISTS idx_outbox_debezium;

CREATE INDEX idx_outbox_pending_created ON outbox_events(status, created_at)
    WHERE status = 'PENDING';
