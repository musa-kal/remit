package ca.musakaleem.remit.api.deadletter;

import java.time.Instant;
import java.util.UUID;

/**
 * Body of a 202 from {@code POST /api/v1/dead-letters/{id}/replay}.
 *
 * @param id dead letter id
 * @param idempotencyKey instruction key
 * @param batchId batch of the instruction
 * @param status REPLAY_REQUESTED
 * @param replayCount the replay count the republished instruction carries
 * @param requestedAt when the replay was published
 */
public record ReplayResponse(
    Long id,
    String idempotencyKey,
    UUID batchId,
    DeadLetterStatus status,
    int replayCount,
    Instant requestedAt) {

  /**
   * Maps a dead letter right after its replay was requested.
   *
   * @param deadLetter the dead letter
   * @return the response
   */
  public static ReplayResponse of(DeadLetter deadLetter) {
    return new ReplayResponse(
        deadLetter.getId(),
        deadLetter.getIdempotencyKey(),
        deadLetter.getBatchId(),
        deadLetter.getStatus(),
        deadLetter.getReplayCount(),
        deadLetter.getLastReplayedAt());
  }
}
