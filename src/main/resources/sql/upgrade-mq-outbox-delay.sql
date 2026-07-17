ALTER TABLE mq_outbox_message
    ADD COLUMN delay_seconds INT NULL AFTER retry_count;
