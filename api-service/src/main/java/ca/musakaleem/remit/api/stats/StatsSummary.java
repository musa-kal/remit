package ca.musakaleem.remit.api.stats;

import ca.musakaleem.remit.api.deadletter.DeadLetterStatus;
import ca.musakaleem.remit.common.event.BatchStatus;
import ca.musakaleem.remit.common.event.InstructionStatus;
import java.time.Instant;
import java.util.Map;

/**
 * {@code GET /api/v1/stats/summary}. Every status is listed, with zero when there is none.
 *
 * @param from start of the range, inclusive, or {@code null} for the beginning
 * @param to end of the range, exclusive, or {@code null} for now
 * @param batches batches per status, by {@code receivedAt}
 * @param instructions instructions per current status, by the time of that status
 * @param duplicateDeliveries DUPLICATE status events in the range (they never change the status)
 * @param rejectedRows rejected rows in the range
 * @param deadLetters dead letters per status, by {@code firstSeenAt}
 */
public record StatsSummary(
    Instant from,
    Instant to,
    Map<BatchStatus, Long> batches,
    Map<InstructionStatus, Long> instructions,
    long duplicateDeliveries,
    long rejectedRows,
    Map<DeadLetterStatus, Long> deadLetters) {}
