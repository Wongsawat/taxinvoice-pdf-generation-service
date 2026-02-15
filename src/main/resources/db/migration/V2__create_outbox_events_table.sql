-- Create outbox_events table for the Transactional Outbox Pattern.
-- Debezium CDC reads this table via PostgreSQL logical replication
-- and routes events to Kafka topics based on the 'topic' column.
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    topic VARCHAR(255),
    partition_key VARCHAR(255),
    headers TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INTEGER DEFAULT 0,
    error_message VARCHAR(1000),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP
);

-- Index for polling publisher (find events awaiting publication)
CREATE INDEX idx_outbox_status ON outbox_events(status);

-- Index for chronological processing
CREATE INDEX idx_outbox_created ON outbox_events(created_at);

-- Partial index optimized for Debezium CDC (only polls PENDING events)
CREATE INDEX idx_outbox_debezium ON outbox_events(created_at) WHERE status = 'PENDING';

-- Index for aggregate lookups (tracing/debugging)
CREATE INDEX idx_outbox_aggregate ON outbox_events(aggregate_id, aggregate_type);
