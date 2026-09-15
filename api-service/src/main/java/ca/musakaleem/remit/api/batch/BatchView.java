package ca.musakaleem.remit.api.batch;

import ca.musakaleem.remit.common.event.BatchStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A batch in {@code GET /api/v1/batches}.
 *
 * @param batchId batch id
 * @param clientId client that sent the file
 * @param fileName original file name
 * @param fileHash SHA-256 of the file
 * @param status RECEIVED, COMPLETED, COMPLETED_WITH_ERRORS or FAILED
 * @param totalRows instruction rows in the file
 * @param acceptedRows rows published as instructions
 * @param rejectedRows rows rejected by validation
 * @param controlTotal trailer control total, {@code null} when unreadable
 * @param failureReason reason code of a FAILED batch, for example DUPLICATE_FILE
 * @param receivedAt when the batch was received
 * @param completedAt when it reached its final status
 * @param updatedAt time of the last applied event
 */
public record BatchView(
    UUID batchId,
    String clientId,
    String fileName,
    String fileHash,
    BatchStatus status,
    int totalRows,
    int acceptedRows,
    int rejectedRows,
    BigDecimal controlTotal,
    String failureReason,
    Instant receivedAt,
    Instant completedAt,
    Instant updatedAt) {

  /**
   * Maps an entity.
   *
   * @param batch the entity
   * @return the view
   */
  public static BatchView of(PaymentBatch batch) {
    return new BatchView(
        batch.getBatchId(),
        batch.getClientId(),
        batch.getFileName(),
        batch.getFileHash(),
        batch.getStatus(),
        batch.getTotalRows(),
        batch.getAcceptedRows(),
        batch.getRejectedRows(),
        batch.getControlTotal(),
        batch.getFailureReason(),
        batch.getReceivedAt(),
        batch.getCompletedAt(),
        batch.getUpdatedAt());
  }
}
