-- Idempotency ledger of routing-service (spec 8.1, FR-RTE-02). One row per instruction key.
-- The primary key on idempotency_key is the unique constraint that makes a second delivery of
-- the same instruction fail its insert, which is how duplicates are detected.
CREATE TABLE processed_instruction (
  idempotency_key CHAR(64)      NOT NULL,
  batch_id        CHAR(36)      NOT NULL,
  channel         VARCHAR(10)   NOT NULL,
  amount          DECIMAL(15,2) NOT NULL,
  -- ROUTING while an attempt is in flight, then ROUTED or REQUIRES_REVIEW.
  status          VARCHAR(20)   NOT NULL,
  -- Number of processing attempts, including retries and replays.
  attempts        INT           NOT NULL,
  created_at      TIMESTAMP(3)  NOT NULL,
  updated_at      TIMESTAMP(3)  NOT NULL,
  -- JPA @Version for optimistic locking.
  version         BIGINT        NOT NULL,
  CONSTRAINT pk_processed_instruction PRIMARY KEY (idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX ix_processed_instruction_batch_id ON processed_instruction (batch_id);
