package ca.musakaleem.remit.routing.ledger;

import ca.musakaleem.remit.common.event.Channel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One instruction in {@code remit_routing.processed_instruction} (spec 8.1): the idempotency ledger
 * that makes routing safe under at-least-once delivery.
 */
@Entity
@Table(name = "processed_instruction")
public class ProcessedInstruction {

  @Id
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "idempotency_key", nullable = false, length = 64, updatable = false)
  private String idempotencyKey;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "batch_id", nullable = false, length = 36, updatable = false)
  private String batchId;

  @Enumerated(EnumType.STRING)
  @Column(name = "channel", nullable = false, length = 10, updatable = false)
  private Channel channel;

  @Column(name = "amount", nullable = false, precision = 15, scale = 2, updatable = false)
  private BigDecimal amount;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private ProcessingStatus status;

  @Column(name = "attempts", nullable = false)
  private int attempts;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /** Optimistic lock: a concurrent update of the same row fails instead of overwriting. */
  @Version
  @Column(name = "version", nullable = false)
  private Long version;

  /** For JPA only. */
  protected ProcessedInstruction() {}

  /**
   * Creates the row that claims an instruction key: status ROUTING, first attempt.
   *
   * @param idempotencyKey instruction key
   * @param batchId batch the instruction arrived in
   * @param channel requested channel
   * @param amount instruction amount
   * @param now current time
   */
  public ProcessedInstruction(
      String idempotencyKey, UUID batchId, Channel channel, BigDecimal amount, Instant now) {
    this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    this.batchId = Objects.requireNonNull(batchId, "batchId").toString();
    this.channel = Objects.requireNonNull(channel, "channel");
    this.amount = Objects.requireNonNull(amount, "amount");
    this.status = ProcessingStatus.ROUTING;
    this.attempts = 1;
    this.createdAt = Objects.requireNonNull(now, "now");
    this.updatedAt = now;
  }

  /**
   * Records one more processing attempt of a row still in ROUTING.
   *
   * @param now current time
   */
  public void recordAttempt(Instant now) {
    if (status.isFinal()) {
      throw new IllegalStateException("Instruction " + idempotencyKey + " is already " + status);
    }
    attempts++;
    updatedAt = Objects.requireNonNull(now, "now");
  }

  /**
   * Records the outcome of a successful attempt.
   *
   * @param outcome ROUTED or REQUIRES_REVIEW
   * @param now current time
   */
  public void finish(ProcessingStatus outcome, Instant now) {
    if (!outcome.isFinal()) {
      throw new IllegalArgumentException(outcome + " is not a final status");
    }
    status = outcome;
    updatedAt = Objects.requireNonNull(now, "now");
  }

  /**
   * Returns the instruction key.
   *
   * @return SHA-256 hex key
   */
  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  /**
   * Returns the batch the instruction first arrived in.
   *
   * @return batch id
   */
  public UUID getBatchId() {
    return UUID.fromString(batchId);
  }

  /**
   * Returns the requested channel.
   *
   * @return channel
   */
  public Channel getChannel() {
    return channel;
  }

  /**
   * Returns the amount.
   *
   * @return amount
   */
  public BigDecimal getAmount() {
    return amount;
  }

  /**
   * Returns the current status.
   *
   * @return status
   */
  public ProcessingStatus getStatus() {
    return status;
  }

  /**
   * Returns how many times processing was attempted.
   *
   * @return attempts, at least 1
   */
  public int getAttempts() {
    return attempts;
  }

  /**
   * Returns when the key was first claimed.
   *
   * @return creation time
   */
  public Instant getCreatedAt() {
    return createdAt;
  }

  /**
   * Returns when the row last changed.
   *
   * @return update time
   */
  public Instant getUpdatedAt() {
    return updatedAt;
  }

  /**
   * Returns the optimistic lock version.
   *
   * @return version, {@code null} before the first insert
   */
  public Long getVersion() {
    return version;
  }
}
