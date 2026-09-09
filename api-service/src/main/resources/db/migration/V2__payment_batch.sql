-- Read model of batch files, projected from payments.batch-events (FR-API-01). One row per batch id.
-- updated_at is the occurredAt of the last applied event, so an older event can be recognised and
-- ignored when events arrive out of order or twice.
CREATE TABLE payment_batch (
  batch_id       CHAR(36)      NOT NULL,
  client_id      VARCHAR(10)   NOT NULL,
  file_name      VARCHAR(100)  NOT NULL,
  -- Not unique: a DUPLICATE_FILE batch carries the hash of the file it duplicates.
  file_hash      CHAR(64)      NOT NULL,
  -- RECEIVED, COMPLETED, COMPLETED_WITH_ERRORS, FAILED
  status         VARCHAR(30)   NOT NULL,
  total_rows     INT           NOT NULL,
  accepted_rows  INT           NOT NULL,
  rejected_rows  INT           NOT NULL,
  -- Declared trailer total; unknown when the trailer could not be read.
  control_total  DECIMAL(15,2) NULL,
  -- reasonCode header of a FAILED batch event, for example DUPLICATE_FILE.
  failure_reason VARCHAR(50)   NULL,
  received_at    TIMESTAMP(3)  NOT NULL,
  completed_at   TIMESTAMP(3)  NULL,
  updated_at     TIMESTAMP(3)  NOT NULL,
  CONSTRAINT pk_payment_batch PRIMARY KEY (batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- GET /batches: newest first, optionally filtered by status or client and a received_at range.
CREATE INDEX ix_payment_batch_received_at ON payment_batch (received_at);
CREATE INDEX ix_payment_batch_status_received ON payment_batch (status, received_at);
CREATE INDEX ix_payment_batch_client_received ON payment_batch (client_id, received_at);
