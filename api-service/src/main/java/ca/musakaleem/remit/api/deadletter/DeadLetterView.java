package ca.musakaleem.remit.api.deadletter;

import java.time.Instant;
import java.util.UUID;

/**
 * A dead letter in {@code GET /api/v1/dead-letters}. The payload is not included: it holds full
 * account numbers.
 *
 * @param id dead letter id, used to replay it
 * @param idempotencyKey instruction key, {@code null} for an unreadable record
 * @param batchId batch, when known
 * @param originalTopic topic the failed record came from
 * @param originalPartition its partition
 * @param originalOffset its offset
 * @param exceptionClass root cause class
 * @param exceptionMessage exception message, account-like digits masked
 * @param status PENDING, REPLAY_REQUESTED, REPLAYED or EXHAUSTED
 * @param replayCount replays so far
 * @param replayable whether a replay would be accepted now
 * @param firstSeenAt when the record was dead-lettered
 * @param lastReplayedAt when it was last replayed
 * @param updatedAt last change
 */
public record DeadLetterView(
    Long id,
    String idempotencyKey,
    UUID batchId,
    String originalTopic,
    int originalPartition,
    long originalOffset,
    String exceptionClass,
    String exceptionMessage,
    DeadLetterStatus status,
    int replayCount,
    boolean replayable,
    Instant firstSeenAt,
    Instant lastReplayedAt,
    Instant updatedAt) {

  /**
   * Maps an entity.
   *
   * @param deadLetter the entity
   * @param maxReplays replay cap
   * @return the view
   */
  public static DeadLetterView of(DeadLetter deadLetter, int maxReplays) {
    boolean replayable =
        deadLetter.getIdempotencyKey() != null
            && deadLetter.getStatus() == DeadLetterStatus.PENDING
            && deadLetter.getReplayCount() < maxReplays;
    return new DeadLetterView(
        deadLetter.getId(),
        deadLetter.getIdempotencyKey(),
        deadLetter.getBatchId(),
        deadLetter.getOriginalTopic(),
        deadLetter.getOriginalPartition(),
        deadLetter.getOriginalOffset(),
        deadLetter.getExceptionClass(),
        deadLetter.getExceptionMessage(),
        deadLetter.getStatus(),
        deadLetter.getReplayCount(),
        replayable,
        deadLetter.getFirstSeenAt(),
        deadLetter.getLastReplayedAt(),
        deadLetter.getUpdatedAt());
  }
}
