package ca.musakaleem.remit.api.batch;

import ca.musakaleem.remit.common.event.BatchStatus;
import ca.musakaleem.remit.common.event.InstructionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * {@code GET /api/v1/batches/{batchId}}: the batch with its counts, control total and what became
 * of its instructions.
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
 * @param failureReason reason code of a FAILED batch
 * @param receivedAt when the batch was received
 * @param completedAt when it reached its final status
 * @param updatedAt time of the last applied event
 * @param instructionsByStatus instructions of this batch per current status, every status listed
 * @param rejectionCount rejected rows stored for this batch
 * @param deadLetterCount dead letters of this batch
 */
public record BatchDetailView(
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
    Instant updatedAt,
    Map<InstructionStatus, Long> instructionsByStatus,
    long rejectionCount,
    long deadLetterCount) {

  /**
   * Maps an entity and its counts.
   *
   * @param batch the entity
   * @param instructionsByStatus instruction counts per status
   * @param rejectionCount rejected rows
   * @param deadLetterCount dead letters
   * @return the view
   */
  public static BatchDetailView of(
      PaymentBatch batch,
      Map<InstructionStatus, Long> instructionsByStatus,
      long rejectionCount,
      long deadLetterCount) {
    return new BatchDetailView(
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
        batch.getUpdatedAt(),
        instructionsByStatus,
        rejectionCount,
        deadLetterCount);
  }
}
