package ca.musakaleem.remit.api.support;

import ca.musakaleem.remit.common.IdempotencyKeys;
import ca.musakaleem.remit.common.event.BatchEvent;
import ca.musakaleem.remit.common.event.BatchStatus;
import ca.musakaleem.remit.common.event.Channel;
import ca.musakaleem.remit.common.event.Party;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Builds events with obviously fake data for tests. */
public final class TestEvents {

  /** Debtor account of every test instruction (fake). */
  public static final String DEBTOR_ACCOUNT = "1000001234";

  /** Creditor account of every test instruction (fake). */
  public static final String CREDITOR_ACCOUNT = "2000005678";

  private TestEvents() {}

  /**
   * An instruction with a fresh reference, key and the given batch.
   *
   * @param batchId batch id
   * @param replayCount replay count
   * @return the instruction
   */
  public static PaymentInstruction instruction(UUID batchId, int replayCount) {
    String ref = "TEST-" + UUID.randomUUID().toString().substring(0, 8);
    return new PaymentInstruction(
        IdempotencyKeys.forInstruction("TEST01", ref),
        batchId,
        "TEST01",
        ref,
        new Party("Test Debtor Ltd", "001", "00011", DEBTOR_ACCOUNT),
        new Party("Test Creditor Inc", "002", "00022", CREDITOR_ACCOUNT),
        new BigDecimal("1250.00"),
        "CAD",
        Channel.EFT,
        LocalDate.of(2026, 10, 15),
        "Invoice 1001",
        replayCount);
  }

  /**
   * A batch event.
   *
   * @param batchId batch id
   * @param status status
   * @param occurredAt when
   * @return the event
   */
  public static BatchEvent batch(UUID batchId, BatchStatus status, Instant occurredAt) {
    boolean done = status != BatchStatus.RECEIVED;
    return new BatchEvent(
        batchId,
        "TEST01",
        "PAY_TEST01_20261015093000.csv",
        "a".repeat(64),
        status,
        10,
        done ? 8 : 0,
        done ? 2 : 0,
        new BigDecimal("12500.00"),
        occurredAt);
  }
}
