package ca.musakaleem.remit.api.deadletter;

import ca.musakaleem.remit.api.messaging.EventTime;
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
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A dead-lettered instruction in {@code remit_api.dead_letter} and its replay lifecycle
 * (docs/decisions/0003-dlt-and-replay.md):
 *
 * <pre>
 * PENDING --replay--> REPLAY_REQUESTED --REPLAYED/ROUTED/REQUIRES_REVIEW--> REPLAYED
 *                          |
 *                          +--DEAD_LETTERED again--> PENDING, or EXHAUSTED after the last replay
 * </pre>
 *
 * <p>One row follows one instruction through its replays: when a replay fails, routing-service
 * dead-letters the replayed record (a new DLT record at a new offset), and that record updates this
 * row instead of creating a second one.
 */
@Entity
@Table(name = "dead_letter")
public class DeadLetter {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "idempotency_key", length = 64, updatable = false)
  private String idempotencyKey;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "batch_id", length = 36, updatable = false)
  private String batchId;

  @Column(name = "original_topic", nullable = false, updatable = false)
  private String originalTopic;

  @Column(name = "original_partition", nullable = false, updatable = false)
  private int originalPartition;

  @Column(name = "original_offset", nullable = false, updatable = false)
  private long originalOffset;

  @Column(name = "exception_class")
  private String exceptionClass;

  @Column(name = "exception_message", length = 1000)
  private String exceptionMessage;

  @Column(name = "payload", nullable = false, columnDefinition = "json")
  private String payload;

  @Column(name = "replay_count", nullable = false)
  private int replayCount;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private DeadLetterStatus status;

  @Column(name = "first_seen_at", nullable = false, updatable = false)
  private Instant firstSeenAt;

  @Column(name = "last_replayed_at")
  private Instant lastReplayedAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /** For JPA only. */
  protected DeadLetter() {}

  /**
   * Creates the row for a newly seen DLT record. It starts with the replay count carried by the
   * instruction, so an instruction replayed elsewhere cannot exceed the cap here.
   *
   * @param record the parsed DLT record
   * @param maxReplays replay cap
   * @return the new row
   */
  public static DeadLetter from(DeadLetterRecord record, int maxReplays) {
    DeadLetter deadLetter = new DeadLetter();
    if (record.readable()) {
      deadLetter.idempotencyKey = record.instruction().idempotencyKey();
      deadLetter.batchId = record.instruction().batchId().toString();
      deadLetter.replayCount = record.instruction().replayCount();
    } else {
      deadLetter.batchId = asBatchId(record.correlationId());
    }
    deadLetter.originalTopic = record.originalTopic();
    deadLetter.originalPartition = record.originalPartition();
    deadLetter.originalOffset = record.originalOffset();
    deadLetter.exceptionClass = record.exceptionClass();
    deadLetter.exceptionMessage = record.exceptionMessage();
    deadLetter.payload = record.payloadJson();
    deadLetter.status =
        deadLetter.replayCount >= maxReplays
            ? DeadLetterStatus.EXHAUSTED
            : DeadLetterStatus.PENDING;
    deadLetter.firstSeenAt = EventTime.millis(record.deadLetteredAt());
    deadLetter.updatedAt = deadLetter.firstSeenAt;
    return deadLetter;
  }

  /**
   * Applies the DLT record of a replay that failed again. A record carrying an older replay count
   * than this row (a late redelivery) changes nothing.
   *
   * @param record the new DLT record of the same instruction
   * @param maxReplays replay cap
   * @return true when the row changed
   */
  public boolean replayFailed(DeadLetterRecord record, int maxReplays) {
    if (!record.readable() || record.instruction().replayCount() < replayCount) {
      return false;
    }
    this.exceptionClass = record.exceptionClass();
    this.exceptionMessage = record.exceptionMessage();
    this.payload = record.payloadJson();
    this.replayCount = record.instruction().replayCount();
    if (status == DeadLetterStatus.REPLAY_REQUESTED) {
      this.status = afterFailedReplay(maxReplays);
    }
    Instant deadLetteredAt = EventTime.millis(record.deadLetteredAt());
    if (deadLetteredAt.isAfter(updatedAt)) {
      this.updatedAt = deadLetteredAt;
    }
    return true;
  }

  /**
   * Checks the replay rules and, if they allow it, records the replay: status REPLAY_REQUESTED,
   * replay count plus one, last replayed now. When the cap is reached the row is marked EXHAUSTED
   * before the exception is thrown.
   *
   * @param maxReplays replay cap
   * @param requestedAt when the replay was requested
   * @throws ReplayRejectedException when the replay is not allowed
   */
  public void requestReplay(int maxReplays, Instant requestedAt) {
    Instant now = EventTime.millis(requestedAt);
    if (idempotencyKey == null) {
      throw new ReplayRejectedException(
          "Dead letter " + id + " holds an unreadable record; it cannot be replayed");
    }
    if (status == DeadLetterStatus.REPLAY_REQUESTED) {
      throw new ReplayRejectedException(
          "A replay of dead letter " + id + " is already pending (replay " + replayCount + ")");
    }
    if (status == DeadLetterStatus.REPLAYED) {
      throw new ReplayRejectedException("Dead letter " + id + " was already replayed successfully");
    }
    if (replayCount >= maxReplays) {
      this.status = DeadLetterStatus.EXHAUSTED;
      this.updatedAt = now;
      throw new ReplayRejectedException(
          "Dead letter " + id + " reached the limit of " + maxReplays + " replays");
    }
    this.replayCount++;
    this.status = DeadLetterStatus.REPLAY_REQUESTED;
    this.lastReplayedAt = now;
    this.updatedAt = now;
  }

  /**
   * Applies a status event of this instruction while a replay is pending. Events that happened
   * before the replay was requested belong to an earlier attempt and are ignored.
   *
   * @param instructionStatus the status routing-service reported
   * @param occurredAt when it happened
   * @param maxReplays replay cap
   * @return true when the row changed
   */
  public boolean onInstructionStatus(
      InstructionStatus instructionStatus, Instant occurredAt, int maxReplays) {
    Instant at = EventTime.millis(occurredAt);
    if (status != DeadLetterStatus.REPLAY_REQUESTED
        || lastReplayedAt == null
        || at.isBefore(lastReplayedAt)) {
      return false;
    }
    switch (instructionStatus) {
      case DEAD_LETTERED -> this.status = afterFailedReplay(maxReplays);
      case REPLAYED, ROUTED, REQUIRES_REVIEW -> this.status = DeadLetterStatus.REPLAYED;
      default -> {
        return false;
      }
    }
    this.updatedAt = at;
    return true;
  }

  private DeadLetterStatus afterFailedReplay(int maxReplays) {
    return replayCount >= maxReplays ? DeadLetterStatus.EXHAUSTED : DeadLetterStatus.PENDING;
  }

  private static String asBatchId(String correlationId) {
    try {
      return correlationId == null ? null : UUID.fromString(correlationId).toString();
    } catch (IllegalArgumentException notAUuid) {
      return null;
    }
  }

  public Long getId() {
    return id;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public UUID getBatchId() {
    return batchId == null ? null : UUID.fromString(batchId);
  }

  public String getOriginalTopic() {
    return originalTopic;
  }

  public int getOriginalPartition() {
    return originalPartition;
  }

  public long getOriginalOffset() {
    return originalOffset;
  }

  public String getExceptionClass() {
    return exceptionClass;
  }

  public String getExceptionMessage() {
    return exceptionMessage;
  }

  public String getPayload() {
    return payload;
  }

  public int getReplayCount() {
    return replayCount;
  }

  public DeadLetterStatus getStatus() {
    return status;
  }

  public Instant getFirstSeenAt() {
    return firstSeenAt;
  }

  public Instant getLastReplayedAt() {
    return lastReplayedAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
