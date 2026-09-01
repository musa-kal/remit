package ca.musakaleem.remit.common.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Batch lifecycle event published by intake-service to {@code payments.batch-events}.
 *
 * @param batchId batch identifier assigned when the file was accepted for processing
 * @param clientId client that submitted the file
 * @param fileName original file name
 * @param fileHash SHA-256 hex of the file content
 * @param status batch status at the time of the event
 * @param totalRows instruction rows in the file
 * @param acceptedRows rows published to {@code payments.instructions}
 * @param rejectedRows rows published to {@code payments.rejected}
 * @param controlTotal control total declared in the trailer, if it could be read
 * @param occurredAt when the event happened (UTC)
 */
public record BatchEvent(
    UUID batchId,
    String clientId,
    String fileName,
    String fileHash,
    BatchStatus status,
    int totalRows,
    int acceptedRows,
    int rejectedRows,
    BigDecimal controlTotal,
    Instant occurredAt) {}
