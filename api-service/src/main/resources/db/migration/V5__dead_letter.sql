-- Records of payments.instructions.DLT (FR-API-01, docs/decisions/0003-dlt-and-replay.md).
-- The same source record can be dead-lettered twice, so the natural key is the original topic,
-- partition and offset. A poison pill has no readable instruction: idempotency_key is null and
-- payload holds the masked raw text instead of an instruction.
CREATE TABLE dead_letter (
  id                 BIGINT        NOT NULL AUTO_INCREMENT,
  idempotency_key    CHAR(64)      NULL,
  batch_id           CHAR(36)      NULL,
  original_topic     VARCHAR(255)  NOT NULL,
  original_partition INT           NOT NULL,
  original_offset    BIGINT        NOT NULL,
  exception_class    VARCHAR(255)  NULL,
  exception_message  VARCHAR(1000) NULL,
  payload            JSON          NOT NULL,
  replay_count       INT           NOT NULL,
  -- PENDING, REPLAY_REQUESTED, REPLAYED, EXHAUSTED
  status             VARCHAR(20)   NOT NULL,
  first_seen_at      TIMESTAMP(3)  NOT NULL,
  last_replayed_at   TIMESTAMP(3)  NULL,
  updated_at         TIMESTAMP(3)  NOT NULL,
  CONSTRAINT pk_dead_letter PRIMARY KEY (id),
  CONSTRAINT ux_dead_letter_origin UNIQUE (original_topic, original_partition, original_offset)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- GET /dead-letters newest first, optionally by status; and lookups by instruction key.
CREATE INDEX ix_dead_letter_first_seen ON dead_letter (first_seen_at);
CREATE INDEX ix_dead_letter_status_first_seen ON dead_letter (status, first_seen_at);
CREATE INDEX ix_dead_letter_key ON dead_letter (idempotency_key);
