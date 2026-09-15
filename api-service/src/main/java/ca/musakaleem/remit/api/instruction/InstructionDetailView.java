package ca.musakaleem.remit.api.instruction;

import ca.musakaleem.remit.common.event.Channel;
import ca.musakaleem.remit.common.event.InstructionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /api/v1/instructions/{idempotencyKey}}: the instruction and its status history. The
 * only response with full account numbers, and only for an ADMIN (FR-SEC-05); {@code
 * accountsMasked} says which one the caller got.
 *
 * @param idempotencyKey instruction key
 * @param batchId batch the instruction arrived in
 * @param clientId client
 * @param instructionRef client reference
 * @param debtor paying party
 * @param creditor receiving party
 * @param amount amount
 * @param currency CAD or USD
 * @param channel EFT, WIRE or INTERAC
 * @param valueDate requested value date
 * @param remittanceInfo remittance text
 * @param status current status
 * @param statusDetail detail of the current status
 * @param replayCount times replayed from the DLT
 * @param updatedAt time of the current status
 * @param accountsMasked false only for an ADMIN caller
 * @param history every status event, oldest first
 */
public record InstructionDetailView(
    String idempotencyKey,
    UUID batchId,
    String clientId,
    String instructionRef,
    PartyView debtor,
    PartyView creditor,
    BigDecimal amount,
    String currency,
    Channel channel,
    LocalDate valueDate,
    String remittanceInfo,
    InstructionStatus status,
    String statusDetail,
    int replayCount,
    Instant updatedAt,
    boolean accountsMasked,
    List<StatusHistoryView> history) {

  /**
   * Maps an entity and its history.
   *
   * @param instruction the entity
   * @param history its status history, oldest first
   * @param unmasked whether to show full account numbers
   * @return the view
   */
  public static InstructionDetailView of(
      InstructionRecord instruction, List<InstructionStatusHistory> history, boolean unmasked) {
    return new InstructionDetailView(
        instruction.getIdempotencyKey(),
        instruction.getBatchId(),
        instruction.getClientId(),
        instruction.getInstructionRef(),
        PartyView.of(instruction.getDebtor(), unmasked),
        PartyView.of(instruction.getCreditor(), unmasked),
        instruction.getAmount(),
        instruction.getCurrency(),
        instruction.getChannel(),
        instruction.getValueDate(),
        instruction.getRemittanceInfo(),
        instruction.getStatus(),
        instruction.getStatusDetail(),
        instruction.getReplayCount(),
        instruction.getUpdatedAt(),
        !unmasked,
        history.stream().map(StatusHistoryView::of).toList());
  }

  /**
   * One status event.
   *
   * @param status the status
   * @param batchId batch named by the event (the new batch for a cross-file DUPLICATE)
   * @param channel channel
   * @param detail detail
   * @param occurredAt when it happened
   */
  public record StatusHistoryView(
      InstructionStatus status, UUID batchId, Channel channel, String detail, Instant occurredAt) {

    /**
     * Maps a history entry.
     *
     * @param entry the entry
     * @return the view
     */
    public static StatusHistoryView of(InstructionStatusHistory entry) {
      return new StatusHistoryView(
          entry.getStatus(),
          entry.getBatchId(),
          entry.getChannel(),
          entry.getDetail(),
          entry.getOccurredAt());
    }
  }
}
