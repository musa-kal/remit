package ca.musakaleem.remit.api.instruction;

import ca.musakaleem.remit.common.event.Channel;
import ca.musakaleem.remit.common.event.InstructionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * An instruction in {@code GET /api/v1/batches/{batchId}/instructions}. Account numbers are always
 * masked here (FR-SEC-05). Detail fields are {@code null} until the routed or dead-letter record
 * with the payment details has been consumed.
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
 */
public record InstructionView(
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
    Instant updatedAt) {

  /**
   * Maps an entity with masked account numbers.
   *
   * @param instruction the entity
   * @return the view
   */
  public static InstructionView masked(InstructionRecord instruction) {
    return new InstructionView(
        instruction.getIdempotencyKey(),
        instruction.getBatchId(),
        instruction.getClientId(),
        instruction.getInstructionRef(),
        PartyView.of(instruction.getDebtor(), false),
        PartyView.of(instruction.getCreditor(), false),
        instruction.getAmount(),
        instruction.getCurrency(),
        instruction.getChannel(),
        instruction.getValueDate(),
        instruction.getRemittanceInfo(),
        instruction.getStatus(),
        instruction.getStatusDetail(),
        instruction.getReplayCount(),
        instruction.getUpdatedAt());
  }
}
