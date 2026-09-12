package ca.musakaleem.remit.api.deadletter;

import ca.musakaleem.remit.api.instruction.InstructionRecord;
import ca.musakaleem.remit.api.instruction.InstructionRecordRepository;
import ca.musakaleem.remit.common.event.InstructionStatus;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps {@code dead_letter} in step with the DLT and with status events (FR-API-01):
 *
 * <ul>
 *   <li>a DLT record already stored under its original topic, partition and offset is a duplicate;
 *   <li>a DLT record of an instruction whose dead letter is still open is a replay that failed
 *       again and updates that row;
 *   <li>any other DLT record creates a row. A readable one also fills in the instruction's details
 *       and marks it DEAD_LETTERED.
 * </ul>
 */
@Service
public class DeadLetterProjection {

  private static final List<DeadLetterStatus> OPEN =
      List.of(
          DeadLetterStatus.REPLAY_REQUESTED, DeadLetterStatus.PENDING, DeadLetterStatus.EXHAUSTED);

  private final DeadLetterRepository deadLetters;
  private final InstructionRecordRepository instructions;
  private final int maxReplays;

  /**
   * Creates the projection.
   *
   * @param deadLetters dead letter repository
   * @param instructions instruction repository
   * @param properties replay cap
   */
  public DeadLetterProjection(
      DeadLetterRepository deadLetters,
      InstructionRecordRepository instructions,
      ReplayProperties properties) {
    this.deadLetters = deadLetters;
    this.instructions = instructions;
    this.maxReplays = properties.maxReplays();
  }

  /**
   * Applies a DLT record.
   *
   * @param record the parsed record
   * @return the affected row and what happened to it
   */
  @Transactional
  public DeadLetterOutcome record(DeadLetterRecord record) {
    Optional<DeadLetter> seen =
        deadLetters.findByOriginalTopicAndOriginalPartitionAndOriginalOffset(
            record.originalTopic(), record.originalPartition(), record.originalOffset());
    if (seen.isPresent()) {
      return new DeadLetterOutcome(seen.get(), DeadLetterOutcome.Kind.ALREADY_RECORDED);
    }
    if (record.readable()) {
      markInstruction(record);
      List<DeadLetter> open =
          deadLetters.findByIdempotencyKeyAndStatusInOrderByIdDesc(
              record.instruction().idempotencyKey(), OPEN);
      if (!open.isEmpty()) {
        DeadLetter existing = open.get(0);
        boolean changed = existing.replayFailed(record, maxReplays);
        return new DeadLetterOutcome(
            existing,
            changed
                ? DeadLetterOutcome.Kind.FAILED_AGAIN
                : DeadLetterOutcome.Kind.ALREADY_RECORDED);
      }
    }
    DeadLetter created = deadLetters.save(DeadLetter.from(record, maxReplays));
    return new DeadLetterOutcome(created, DeadLetterOutcome.Kind.CREATED);
  }

  /**
   * Applies a status event to the instruction's dead letters that wait for a replay outcome.
   *
   * @param idempotencyKey instruction key
   * @param status reported status
   * @param occurredAt when it happened
   * @return the rows that changed
   */
  @Transactional
  public List<DeadLetter> onInstructionStatus(
      String idempotencyKey, InstructionStatus status, Instant occurredAt) {
    return deadLetters
        .findByIdempotencyKeyAndStatusInOrderByIdDesc(
            idempotencyKey, List.of(DeadLetterStatus.REPLAY_REQUESTED))
        .stream()
        .filter(deadLetter -> deadLetter.onInstructionStatus(status, occurredAt, maxReplays))
        .toList();
  }

  private void markInstruction(DeadLetterRecord record) {
    PaymentInstruction instruction = record.instruction();
    String detail = record.exceptionMessage();
    InstructionRecord row =
        instructions
            .findById(instruction.idempotencyKey())
            .orElseGet(
                () ->
                    instructions.save(
                        new InstructionRecord(
                            instruction.idempotencyKey(),
                            instruction.batchId(),
                            InstructionStatus.DEAD_LETTERED,
                            instruction.channel(),
                            detail,
                            record.deadLetteredAt())));
    row.applyDetails(instruction);
    row.applyStatus(
        InstructionStatus.DEAD_LETTERED, instruction.channel(), detail, record.deadLetteredAt());
  }
}
