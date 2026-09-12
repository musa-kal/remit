package ca.musakaleem.remit.api.batch;

import ca.musakaleem.remit.common.event.BatchEvent;
import ca.musakaleem.remit.common.event.BatchStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One batch in {@code remit_api.payment_batch}, projected from {@code payments.batch-events}.
 *
 * <p>{@code updatedAt} holds the {@code occurredAt} of the last applied event. An event that
 * happened strictly before it is ignored, so replaying or reordering events never moves a batch
 * back; an event with the same time is applied again, which makes redelivery harmless.
 */
@Entity
@Table(name = "payment_batch")
public class PaymentBatch {

  @Id
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "batch_id", nullable = false, length = 36, updatable = false)
  private String batchId;

  @Column(name = "client_id", nullable = false, length = 10)
  private String clientId;

  @Column(name = "file_name", nullable = false, length = 100)
  private String fileName;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "file_hash", nullable = false, length = 64)
  private String fileHash;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 30)
  private BatchStatus status;

  @Column(name = "total_rows", nullable = false)
  private int totalRows;

  @Column(name = "accepted_rows", nullable = false)
  private int acceptedRows;

  @Column(name = "rejected_rows", nullable = false)
  private int rejectedRows;

  @Column(name = "control_total", precision = 15, scale = 2)
  private BigDecimal controlTotal;

  @Column(name = "failure_reason", length = 50)
  private String failureReason;

  @Column(name = "received_at", nullable = false)
  private Instant receivedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /** For JPA only. */
  protected PaymentBatch() {}

  /**
   * Creates the row from the first event seen for a batch. That is normally RECEIVED, but a file
   * rejected as a whole (for example DUPLICATE_FILE) only ever gets a FAILED event.
   *
   * @param event the event
   * @param reasonCode the {@code reasonCode} header of a FAILED event, or {@code null}
   * @return the new row
   */
  public static PaymentBatch from(BatchEvent event, String reasonCode) {
    PaymentBatch batch = new PaymentBatch();
    batch.batchId = Objects.requireNonNull(event.batchId(), "batchId").toString();
    batch.receivedAt = Objects.requireNonNull(event.occurredAt(), "occurredAt");
    batch.copy(event, reasonCode);
    return batch;
  }

  /**
   * Applies a later event of the same batch.
   *
   * @param event the event
   * @param reasonCode the {@code reasonCode} header of a FAILED event, or {@code null}
   * @return true when applied, false when ignored as older than the current state
   */
  public boolean apply(BatchEvent event, String reasonCode) {
    if (event.occurredAt().isBefore(updatedAt)) {
      return false;
    }
    if (event.status() == BatchStatus.RECEIVED && status != BatchStatus.RECEIVED) {
      // A final status never goes back to RECEIVED, even with an equal timestamp.
      return false;
    }
    copy(event, reasonCode);
    return true;
  }

  private void copy(BatchEvent event, String reasonCode) {
    this.clientId = event.clientId();
    this.fileName = event.fileName();
    this.fileHash = event.fileHash();
    this.status = Objects.requireNonNull(event.status(), "status");
    this.totalRows = event.totalRows();
    this.acceptedRows = event.acceptedRows();
    this.rejectedRows = event.rejectedRows();
    if (event.controlTotal() != null) {
      this.controlTotal = event.controlTotal();
    }
    if (event.status() != BatchStatus.RECEIVED) {
      this.completedAt = event.occurredAt();
    }
    this.failureReason = event.status() == BatchStatus.FAILED ? reasonCode : null;
    this.updatedAt = event.occurredAt();
  }

  public UUID getBatchId() {
    return UUID.fromString(batchId);
  }

  public String getClientId() {
    return clientId;
  }

  public String getFileName() {
    return fileName;
  }

  public String getFileHash() {
    return fileHash;
  }

  public BatchStatus getStatus() {
    return status;
  }

  public int getTotalRows() {
    return totalRows;
  }

  public int getAcceptedRows() {
    return acceptedRows;
  }

  public int getRejectedRows() {
    return rejectedRows;
  }

  public BigDecimal getControlTotal() {
    return controlTotal;
  }

  public String getFailureReason() {
    return failureReason;
  }

  public Instant getReceivedAt() {
    return receivedAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
