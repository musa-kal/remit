package ca.musakaleem.remit.api.deadletter;

import ca.musakaleem.remit.api.web.NotFoundException;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Replays a dead letter (spec 7.3): republishes its stored instruction with {@code replayCount + 1}
 * and records the replay. The row is locked for the whole transaction, so two administrators
 * replaying at once get one 202 and one 409. If Kafka does not acknowledge, the transaction rolls
 * back and nothing changes. A refused replay commits, so a dead letter found over the cap stays
 * EXHAUSTED.
 */
@Service
public class ReplayService {

  private static final Logger log = LoggerFactory.getLogger(ReplayService.class);

  private final DeadLetterRepository deadLetters;
  private final DeadLetterRecordParser parser;
  private final ReplayPublisher publisher;
  private final Clock clock;
  private final int maxReplays;

  /**
   * Creates the service.
   *
   * @param deadLetters dead letter repository
   * @param parser reads the stored payload
   * @param publisher publishes to {@code payments.instructions}
   * @param clock clock for the replay time
   * @param properties replay cap
   */
  public ReplayService(
      DeadLetterRepository deadLetters,
      DeadLetterRecordParser parser,
      ReplayPublisher publisher,
      Clock clock,
      ReplayProperties properties) {
    this.deadLetters = deadLetters;
    this.parser = parser;
    this.publisher = publisher;
    this.clock = clock;
    this.maxReplays = properties.maxReplays();
  }

  /**
   * Replays a dead letter.
   *
   * @param id dead letter id
   * @return the dead letter after the replay was published
   * @throws NotFoundException when the id is unknown
   * @throws ReplayRejectedException when the cap is reached, a replay is pending, it was replayed
   *     successfully, or the record is unreadable
   */
  @Transactional(noRollbackFor = ReplayRejectedException.class)
  public DeadLetter replay(long id) {
    DeadLetter deadLetter =
        deadLetters
            .findByIdForUpdate(id)
            .orElseThrow(() -> new NotFoundException("Dead letter " + id + " not found"));
    deadLetter.requestReplay(maxReplays, clock.instant());
    PaymentInstruction stored = parser.readStored(deadLetter.getPayload());
    publisher.publish(withReplayCount(stored, deadLetter.getReplayCount()));
    log.info(
        "Replayed dead letter {} as replay {} of {}", id, deadLetter.getReplayCount(), maxReplays);
    return deadLetter;
  }

  /**
   * A copy of the instruction with another replay count.
   *
   * @param source the stored instruction
   * @param replayCount the new count
   * @return the copy
   */
  static PaymentInstruction withReplayCount(PaymentInstruction source, int replayCount) {
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
}
