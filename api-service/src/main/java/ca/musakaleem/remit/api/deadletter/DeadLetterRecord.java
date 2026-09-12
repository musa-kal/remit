package ca.musakaleem.remit.api.deadletter;

import ca.musakaleem.remit.common.event.PaymentInstruction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * A record of {@code payments.instructions.DLT}, parsed by {@link DeadLetterRecordParser}.
 *
 * @param originalTopic topic the failed record came from
 * @param originalPartition its partition
 * @param originalOffset its offset
 * @param exceptionClass root cause class, or the exception class when no cause is given
 * @param exceptionMessage exception message, account-like digits masked, at most 1000 characters
 * @param instruction the instruction, or {@code null} for a poison pill
 * @param payloadJson what is stored: the instruction as JSON, or a JSON object describing the
 *     unreadable bytes
 * @param deadLetteredAt when the record was written to the DLT
 * @param correlationId the {@code correlationId} header (batch id), may be {@code null}
 */
public record DeadLetterRecord(
    String originalTopic,
    int originalPartition,
    long originalOffset,
    String exceptionClass,
    String exceptionMessage,
    PaymentInstruction instruction,
    String payloadJson,
    Instant deadLetteredAt,
    String correlationId) {

  /**
   * Whether the payload is a replayable instruction.
   *
   * @return false for a poison pill
   */
  public boolean readable() {
    return instruction != null;
  }

  /**
   * A stable id for the audit document of this record, derived from where the failed record came
   * from, so the same failure is audited once however often it is dead-lettered or consumed.
   *
   * @return a name-based UUID
   */
  public UUID auditEventId() {
    String origin = "dlt:" + originalTopic + ":" + originalPartition + ":" + originalOffset;
    return UUID.nameUUIDFromBytes(origin.getBytes(StandardCharsets.UTF_8));
  }
}
