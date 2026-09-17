package ca.musakaleem.remit.api.instruction;

import ca.musakaleem.remit.api.messaging.EventTime;
import ca.musakaleem.remit.common.event.Channel;
import ca.musakaleem.remit.common.event.InstructionStatus;
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

/**
 * One status event of an instruction, kept for {@code GET /api/v1/instructions/{key}}. Unlike the
 * current status, the history keeps every event, including DUPLICATE and events that arrived late.
 */
@Entity
@Table(name = "instruction_status_history")
public class InstructionStatusHistory {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "event_id", nullable = false, length = 36, updatable = false)
  private String eventId;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "idempotency_key", nullable = false, length = 64, updatable = false)
  private String idempotencyKey;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "batch_id", length = 36, updatable = false)
  private String batchId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20, updatable = false)
  private InstructionStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "channel", length = 10, updatable = false)
  private Channel channel;

  @Column(name = "detail", length = InstructionRecord.MAX_DETAIL_LENGTH, updatable = false)
  private String detail;

  @Column(name = "occurred_at", nullable = false, updatable = false)
  private Instant occurredAt;

  /** For JPA only. */
  protected InstructionStatusHistory() {}

  /**
   * Creates a history entry.
   *
   * @param eventId the event's {@code eventId} header
   * @param idempotencyKey instruction key
   * @param batchId batch named by the event, may be {@code null}
   * @param status the status
   * @param channel channel, may be {@code null}
   * @param detail detail, may be {@code null}
   * @param occurredAt when it happened
   */
  public InstructionStatusHistory(
      UUID eventId,
      String idempotencyKey,
      UUID batchId,
      InstructionStatus status,
      Channel channel,
      String detail,
      Instant occurredAt) {
    this.eventId = Objects.requireNonNull(eventId, "eventId").toString();
    this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    this.batchId = batchId == null ? null : batchId.toString();
    this.status = Objects.requireNonNull(status, "status");
    this.channel = channel;
    this.detail =
        detail == null || detail.length() <= InstructionRecord.MAX_DETAIL_LENGTH
            ? detail
            : detail.substring(0, InstructionRecord.MAX_DETAIL_LENGTH);
    this.occurredAt = EventTime.millis(Objects.requireNonNull(occurredAt, "occurredAt"));
  }

  public Long getId() {
    return id;
  }

  public String getEventId() {
    return eventId;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public UUID getBatchId() {
    return batchId == null ? null : UUID.fromString(batchId);
  }

  public InstructionStatus getStatus() {
    return status;
  }

  public Channel getChannel() {
    return channel;
  }

  public String getDetail() {
    return detail;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }
}
