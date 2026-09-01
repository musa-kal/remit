package ca.musakaleem.remit.common.event;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A validated payment instruction published by intake-service to {@code payments.instructions}.
 *
 * @param idempotencyKey SHA-256 hex of {@code clientId + "|" + instructionRef}
 * @param batchId batch the instruction arrived in
 * @param clientId client that submitted the file
 * @param instructionRef client reference, unique per client
 * @param debtor paying party
 * @param creditor receiving party
 * @param amount amount with at most two decimal places
 * @param currency ISO currency code, CAD or USD
 * @param channel payment channel
 * @param valueDate requested value date
 * @param remittanceInfo optional free text, at most 140 characters
 * @param replayCount number of times the instruction was replayed from the dead-letter topic
 */
public record PaymentInstruction(
    String idempotencyKey,
    UUID batchId,
    String clientId,
    String instructionRef,
    Party debtor,
    Party creditor,
    BigDecimal amount,
    String currency,
    Channel channel,
    LocalDate valueDate,
    String remittanceInfo,
    int replayCount) {}
