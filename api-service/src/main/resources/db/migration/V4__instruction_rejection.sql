-- Rows rejected by intake-service, projected from payments.rejected (FR-API-01). A file line is
-- rejected at most once, so (batch_id, line_number) is the natural key that makes redelivery a
-- no-op. raw_line_masked already has account digits masked by intake-service.
CREATE TABLE instruction_rejection (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  batch_id        CHAR(36)     NOT NULL,
  client_id       VARCHAR(10)  NULL,
  line_number     BIGINT       NOT NULL,
  -- As supplied in the file, so possibly blank or longer than a valid reference.
  instruction_ref VARCHAR(100) NULL,
  -- Comma separated reason codes, for example INVALID_ACCOUNT,INVALID_AMOUNT.
  reason_codes    VARCHAR(500) NOT NULL,
  raw_line_masked TEXT         NULL,
  occurred_at     TIMESTAMP(3) NOT NULL,
  CONSTRAINT pk_instruction_rejection PRIMARY KEY (id),
  CONSTRAINT ux_instruction_rejection_line UNIQUE (batch_id, line_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- GET /stats/summary counts rejections within a range (GET /batches/{id}/rejections uses the
-- unique key above).
CREATE INDEX ix_instruction_rejection_occurred ON instruction_rejection (occurred_at);
