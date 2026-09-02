package ca.musakaleem.remit.intake.registry;

import ca.musakaleem.remit.common.event.BatchStatus;
import ca.musakaleem.remit.intake.validation.ReasonCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One received file in {@code remit_intake.file_registry} (spec 8.1). */
@Entity
@Table(name = "file_registry")
public class FileRegistryEntry {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "batch_id", nullable = false, length = 36, updatable = false)
  private String batchId;

  @Column(name = "client_id", nullable = false, length = 10, updatable = false)
  private String clientId;

  @Column(name = "file_name", nullable = false, length = 100, updatable = false)
  private String fileName;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "file_hash", nullable = false, length = 64, updatable = false)
  private String fileHash;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 30)
  private BatchStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "reason_code", length = 40)
  private ReasonCode reasonCode;

  @Column(name = "received_at", nullable = false, updatable = false)
  private Instant receivedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  /** For JPA only. */
  protected FileRegistryEntry() {}

  /**
   * Creates a registry row for a newly received file.
   *
   * @param batchId batch id assigned to the file
   * @param clientId client from the file name
   * @param fileName file name
   * @param fileHash SHA-256 hex of the content
   * @param receivedAt when the file was received
   */
  public FileRegistryEntry(
      UUID batchId, String clientId, String fileName, String fileHash, Instant receivedAt) {
    this.batchId = batchId.toString();
    this.clientId = Objects.requireNonNull(clientId);
    this.fileName = Objects.requireNonNull(fileName);
    this.fileHash = Objects.requireNonNull(fileHash);
    this.receivedAt = Objects.requireNonNull(receivedAt);
    this.status = BatchStatus.RECEIVED;
  }

  /**
   * Records the final status of the batch.
   *
   * @param finalStatus COMPLETED, COMPLETED_WITH_ERRORS or FAILED
   * @param reason why the batch failed, or {@code null}
   * @param at when processing finished
   */
  public void complete(BatchStatus finalStatus, ReasonCode reason, Instant at) {
    if (finalStatus == BatchStatus.RECEIVED) {
      throw new IllegalArgumentException("RECEIVED is not a final status");
    }
    this.status = finalStatus;
    this.reasonCode = reason;
    this.completedAt = Objects.requireNonNull(at);
  }

  /**
   * Returns the surrogate key.
   *
   * @return database id
   */
  public Long getId() {
    return id;
  }

  /**
   * Returns the batch id.
   *
   * @return batch id
   */
  public UUID getBatchId() {
    return UUID.fromString(batchId);
  }

  /**
   * Returns the client id.
   *
   * @return client id
   */
  public String getClientId() {
    return clientId;
  }

  /**
   * Returns the file name.
   *
   * @return file name
   */
  public String getFileName() {
    return fileName;
  }

  /**
   * Returns the content hash.
   *
   * @return SHA-256 hex
   */
  public String getFileHash() {
    return fileHash;
  }

  /**
   * Returns the current status.
   *
   * @return status
   */
  public BatchStatus getStatus() {
    return status;
  }

  /**
   * Returns the failure reason, if any.
   *
   * @return reason code or {@code null}
   */
  public ReasonCode getReasonCode() {
    return reasonCode;
  }

  /**
   * Returns when the file was received.
   *
   * @return receive instant
   */
  public Instant getReceivedAt() {
    return receivedAt;
  }

  /**
   * Returns when processing finished.
   *
   * @return completion instant or {@code null}
   */
  public Instant getCompletedAt() {
    return completedAt;
  }
}
