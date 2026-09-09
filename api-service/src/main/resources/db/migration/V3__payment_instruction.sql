-- Read model of instructions, keyed by idempotency key (FR-API-01). Status comes from
-- payments.status-events; the payment details come from payments.routed.* and dead-letter records.
-- Detail columns are nullable because a status event can arrive before the details.
CREATE TABLE payment_instruction (
  idempotency_key      CHAR(64)      NOT NULL,
  batch_id             CHAR(36)      NOT NULL,
  instruction_ref      VARCHAR(35)   NULL,
  client_id            VARCHAR(10)   NULL,
  debtor_name          VARCHAR(70)   NULL,
  debtor_institution   VARCHAR(3)    NULL,
  debtor_transit       VARCHAR(5)    NULL,
  debtor_account       VARCHAR(12)   NULL,
  creditor_name        VARCHAR(70)   NULL,
  creditor_institution VARCHAR(3)    NULL,
  creditor_transit     VARCHAR(5)    NULL,
  creditor_account     VARCHAR(12)   NULL,
  amount               DECIMAL(15,2) NULL,
  currency             VARCHAR(3)    NULL,
  channel              VARCHAR(10)   NULL,
  value_date           DATE          NULL,
  remittance_info      VARCHAR(140)  NULL,
  -- ROUTED, REQUIRES_REVIEW, DUPLICATE, DEAD_LETTERED, REPLAYED
  status               VARCHAR(20)   NOT NULL,
  status_detail        VARCHAR(500)  NULL,
  replay_count         INT           NOT NULL,
  -- occurredAt of the last applied status event.
  updated_at           TIMESTAMP(3)  NOT NULL,
  CONSTRAINT pk_payment_instruction PRIMARY KEY (idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- GET /batches/{id}/instructions filtered by status or channel, and the status counts.
CREATE INDEX ix_payment_instruction_batch_status ON payment_instruction (batch_id, status);
CREATE INDEX ix_payment_instruction_batch_channel ON payment_instruction (batch_id, channel);
-- GET /stats/summary counts by status within an updated_at range.
CREATE INDEX ix_payment_instruction_status_updated ON payment_instruction (status, updated_at);

-- Every status event received for an instruction, for GET /instructions/{key}. event_id is the
-- eventId header, so a redelivered event is stored once. batch_id is the batch named by the event,
-- which for a cross-file DUPLICATE is the new batch.
CREATE TABLE instruction_status_history (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  event_id        CHAR(36)     NOT NULL,
  idempotency_key CHAR(64)     NOT NULL,
  batch_id        CHAR(36)     NULL,
  status          VARCHAR(20)  NOT NULL,
  channel         VARCHAR(10)  NULL,
  detail          VARCHAR(500) NULL,
  occurred_at     TIMESTAMP(3) NOT NULL,
  CONSTRAINT pk_instruction_status_history PRIMARY KEY (id),
  CONSTRAINT ux_instruction_status_history_event UNIQUE (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX ix_status_history_key_occurred ON instruction_status_history (idempotency_key, occurred_at);
-- GET /stats/summary counts duplicate deliveries within a range.
CREATE INDEX ix_status_history_status_occurred ON instruction_status_history (status, occurred_at);
