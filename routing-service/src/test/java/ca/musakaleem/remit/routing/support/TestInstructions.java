package ca.musakaleem.remit.routing.support;

import ca.musakaleem.remit.common.IdempotencyKeys;
import ca.musakaleem.remit.common.event.Channel;
import ca.musakaleem.remit.common.event.Party;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Builds payment instructions with obviously fake parties for tests. */
public final class TestInstructions {

  /** Debtor account used by every test instruction (fake). */
  public static final String DEBTOR_ACCOUNT = "1000001234";

  /** Creditor account used by every test instruction (fake). */
  public static final String CREDITOR_ACCOUNT = "2000005678";

  private TestInstructions() {}

  /**
   * An instruction with a fresh reference, key and batch id.
   *
   * @param channel channel
   * @param amount amount, for example {@code "1250.00"}
   * @param currency CAD or USD
   * @return the instruction
   */
  public static PaymentInstruction instruction(Channel channel, String amount, String currency) {
    String ref = "TEST-" + UUID.randomUUID().toString().substring(0, 8);
    return new PaymentInstruction(
        IdempotencyKeys.forInstruction("TEST01", ref),
        UUID.randomUUID(),
        "TEST01",
        ref,
        new Party("Test Debtor Ltd", "001", "00011", DEBTOR_ACCOUNT),
        new Party("Test Creditor Inc", "002", "00022", CREDITOR_ACCOUNT),
        new BigDecimal(amount),
        currency,
        channel,
        LocalDate.of(2026, 10, 15),
        "Invoice 1001",
        0);
  }

  /**
   * A copy with other remittance info.
   *
   * @param source instruction to copy
   * @param remittanceInfo new remittance info
   * @return the copy
   */
  public static PaymentInstruction withRemittance(
      PaymentInstruction source, String remittanceInfo) {
    return new PaymentInstruction(
        source.idempotencyKey(),
        source.batchId(),
        source.clientId(),
        source.instructionRef(),
        source.debtor(),
        source.creditor(),
        source.amount(),
        source.currency(),
        source.channel(),
        source.valueDate(),
        remittanceInfo,
        source.replayCount());
  }

  /**
   * A copy with another replay count, as api-service would republish it.
   *
   * @param source instruction to copy
   * @param replayCount new replay count
   * @return the copy
   */
  public static PaymentInstruction withReplayCount(PaymentInstruction source, int replayCount) {
    return new PaymentInstruction(
        source.idempotencyKey(),
        source.batchId(),
        source.clientId(),
        source.instructionRef(),
        source.debtor(),
        source.creditor(),
        source.amount(),
        source.currency(),
        source.channel(),
        source.valueDate(),
        source.remittanceInfo(),
        replayCount);
  }

  /**
   * A copy with another key, batch id and amount; other fields unchanged.
   *
   * @param source instruction to copy
   * @param idempotencyKey new key, may be invalid
   * @param batchId new batch id, may be {@code null}
   * @param amount new amount, may be {@code null}
   * @return the copy
   */
  public static PaymentInstruction withKeyBatchAndAmount(
      PaymentInstruction source, String idempotencyKey, UUID batchId, BigDecimal amount) {
    return new PaymentInstruction(
        idempotencyKey,
        batchId,
        source.clientId(),
        source.instructionRef(),
        source.debtor(),
        source.creditor(),
        amount,
        source.currency(),
        source.channel(),
        source.valueDate(),
        source.remittanceInfo(),
        source.replayCount());
  }
}
