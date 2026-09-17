package ca.musakaleem.remit.api.instruction;

import ca.musakaleem.remit.api.deadletter.DeadLetter;
import ca.musakaleem.remit.api.deadletter.DeadLetterProjection;
import ca.musakaleem.remit.common.event.InstructionStatus;
import ca.musakaleem.remit.common.event.InstructionStatusEvent;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Upserts {@code payment_instruction} and {@code instruction_status_history} from status events and
 * routed records (FR-API-01), and tells open dead letters about the outcome of their replay.
 */
@Service
public class InstructionProjection {

  private final InstructionRecordRepository instructions;
  private final InstructionStatusHistoryRepository history;
  private final DeadLetterProjection deadLetters;

  /**
   * Creates the projection.
   *
   * @param instructions instruction repository
   * @param history status history repository
   * @param deadLetters dead letter projection
   */
  public InstructionProjection(
      InstructionRecordRepository instructions,
      InstructionStatusHistoryRepository history,
      DeadLetterProjection deadLetters) {
    this.instructions = instructions;
    this.history = history;
    this.deadLetters = deadLetters;
  }

  /**
   * Applies a status event: adds it to the history (once per event id), updates the current status
   * unless the event is older or a DUPLICATE, and updates a dead letter waiting for this replay.
   *
   * @param eventId the event's id
   * @param event the event, with its {@code occurredAt} set
   * @return what changed
   */
  @Transactional
  public StatusOutcome applyStatus(UUID eventId, InstructionStatusEvent event) {
    boolean recorded = false;
    if (!history.existsByEventId(eventId.toString())) {
      history.save(
          new InstructionStatusHistory(
              eventId,
              event.idempotencyKey(),
              event.batchId(),
              event.status(),
              event.channel(),
              event.detail(),
              event.occurredAt()));
      recorded = true;
    }
    boolean applied;
    if (event.batchId() != null) {
      InstructionRecord row =
          instructions.claim(
              event.idempotencyKey(),
              event.batchId(),
              event.status(),
              event.channel(),
              event.occurredAt());
      applied =
          row.applyStatus(event.status(), event.channel(), event.detail(), event.occurredAt());
    } else {
      applied =
          instructions
              .findForUpdate(event.idempotencyKey())
              .map(
                  row ->
                      row.applyStatus(
                          event.status(), event.channel(), event.detail(), event.occurredAt()))
              .orElse(false);
    }
    List<DeadLetter> changed =
        deadLetters.onInstructionStatus(event.idempotencyKey(), event.status(), event.occurredAt());
    return new StatusOutcome(applied, recorded, changed.stream().map(DeadLetter::getId).toList());
  }

  /**
   * Applies a record of a {@code payments.routed.*} topic: fills in the payment details, and
   * creates the row with the routing status if no status event has arrived yet.
   *
   * @param instruction the routed instruction
   * @param routingStatus the {@code routingStatus} header: ROUTED or REQUIRES_REVIEW
   * @param occurredAt the record's {@code occurredAt}
   */
  @Transactional
  public void applyRouted(
      PaymentInstruction instruction, InstructionStatus routingStatus, Instant occurredAt) {
    instructions
        .claim(
            instruction.idempotencyKey(),
            instruction.batchId(),
            routingStatus,
            instruction.channel(),
            occurredAt)
        .applyDetails(instruction);
  }

  /**
   * What a status event changed.
   *
   * @param statusApplied whether the current status changed
   * @param historyRecorded whether the event was new to the history
   * @param deadLettersUpdated ids of dead letters whose replay outcome this event settled
   */
  public record StatusOutcome(
      boolean statusApplied, boolean historyRecorded, List<Long> deadLettersUpdated) {}
}
