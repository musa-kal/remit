-- File registry (spec 8.1): one row per file that passed the file name check and was not a
-- duplicate. The unique key on file_hash is the duplicate file guard (spec 3.4, 3.6).
CREATE TABLE file_registry (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  batch_id     CHAR(36)     NOT NULL,
  client_id    VARCHAR(10)  NOT NULL,
  file_name    VARCHAR(100) NOT NULL,
  file_hash    CHAR(64)     NOT NULL,
  status       VARCHAR(30)  NOT NULL,
  -- File-level reason code when status is FAILED, for example CONTROL_TOTAL_MISMATCH.
  reason_code  VARCHAR(40)  NULL,
  received_at  TIMESTAMP(3) NOT NULL,
  completed_at TIMESTAMP(3) NULL,
  CONSTRAINT pk_file_registry PRIMARY KEY (id),
  CONSTRAINT uk_file_registry_batch_id UNIQUE (batch_id),
  CONSTRAINT uk_file_registry_file_hash UNIQUE (file_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX ix_file_registry_client_received ON file_registry (client_id, received_at);
