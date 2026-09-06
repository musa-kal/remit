package ca.musakaleem.remit.routing.service;

import ca.musakaleem.remit.common.MaskingUtil;
import ca.musakaleem.remit.common.event.InstructionStatus;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import ca.musakaleem.remit.routing.ledger.ProcessedInstruction;
import ca.musakaleem.remit.routing.ledger.ProcessedInstructionRepository;
import ca.musakaleem.remit.routing.ledger.ProcessingStatus;
import ca.musakaleem.remit.routing.publish.RoutingEventPublisher;
import ca.musakaleem.remit.routing.rules.ChaosHook;
import ca.musakaleem.remit.routing.rules.RoutingDecision;
import ca.musakaleem.remit.routing.rules.RoutingRules;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The idempotent routing algorithm of FR-RTE-02 (docs/decisions/0002-idempotency.md).
 *
 * <p>Transaction boundaries are explicit, with a {@link TransactionTemplate}, so it is visible that
 * no database transaction stays open while waiting for Kafka:
 *
 * <ol>
 *   <li>Transaction 1 claims the idempotency key: insert a ROUTING row; if the key exists, load the
 *       row instead and count the attempt.
 *   <li>A final row (ROUTED or REQUIRES_REVIEW) means this delivery is a duplicate: publish
 *       DUPLICATE and stop. A ROUTING row means an earlier attempt did not finish: carry on.
 *   <li>Outside any transaction: apply the channel rules and publish to {@code
 *       payments.routed.<channel>}, waiting for the acknowledgement.
 *   <li>Transaction 2 records ROUTED or REQUIRES_REVIEW. After it commits, publish the status
 *       event(s). Returning normally lets the listener container commit the offset (the ack).
 * </ol>
 *
 * <p>Any exception propagates to the container's error handler, which retries or dead-letters the
 * record (docs/decisions/0003-dlt-and-replay.md).
 */
@Service
public class RoutingService {

  private static final Logger log = LoggerFactory.getLogger(RoutingService.class);

  private final ProcessedInstructionRepository ledger;
  private final TransactionTemplate transactions;
  private final RoutingRules rules;
  private final ChaosHook chaosHook;
  private final RoutingEventPublisher publisher;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param ledger the processed instruction repository
   * @param transactionManager JPA transaction manager
   * @param rules channel rules and review threshold
   * @param chaosHook FORCE_FAIL hook (FR-RTE-04)
   * @param publisher routed and status event publisher
   * @param clock clock for ledger timestamps
   */
  public RoutingService(
      ProcessedInstructionRepository ledger,
      PlatformTransactionManager transactionManager,
      RoutingRules rules,
      ChaosHook chaosHook,
      RoutingEventPublisher publisher,
      Clock clock) {
    this.ledger = ledger;
    this.transactions = new TransactionTemplate(transactionManager);
    this.rules = rules;
    this.chaosHook = chaosHook;
    this.publisher = publisher;
    this.clock = clock;
  }

  /**
   * Routes one consumed instruction exactly once in effect, however often it is delivered.
   *
   * @param instruction the consumed instruction, {@code null} for an empty record
   */
  public void route(PaymentInstruction instruction) {
    rules.requireRoutable(instruction);

    // Steps 1 and 2: claim the key, or find out that an earlier delivery already routed it.
    ProcessingStatus claimed = claim(instruction);
    if (claimed.isFinal()) {
      log.info("Duplicate delivery of an instruction already {}; not routed again", claimed);
      publisher.publishStatus(instruction, InstructionStatus.DUPLICATE, "already " + claimed);
      return;
    }

    // Step 3: decide and publish to the channel topic. No transaction is open while we wait.
    chaosHook.apply(instruction);
    RoutingDecision decision = rules.decide(instruction);
    publisher.publishRouted(instruction, decision);

    // Step 4: record the outcome and commit, then announce it.
    transactions.executeWithoutResult(
        tx -> finish(instruction.idempotencyKey(), decision.outcome()));
    if (instruction.replayCount() > 0) {
      publisher.publishStatus(
          instruction, InstructionStatus.REPLAYED, "replay " + instruction.replayCount());
    }
    publisher.publishStatus(instruction, decision.instructionStatus(), null);

    log.info(
        "Routed {} {} {} to {} as {} (debtor account {}, replay {})",
        instruction.channel(),
        instruction.amount().toPlainString(),
        instruction.currency(),
        decision.topic(),
        decision.outcome(),
        MaskingUtil.maskAccount(instruction.debtor().account()),
        instruction.replayCount());
  }

  /**
   * Steps 1 and 2 of FR-RTE-02, each in its own transaction.
   *
   * @return ROUTING when this attempt owns the key, otherwise the final status of the earlier one
   */
  private ProcessingStatus claim(PaymentInstruction instruction) {
    try {
      transactions.executeWithoutResult(
          tx ->
              ledger.saveAndFlush(
                  new ProcessedInstruction(
                      instruction.idempotencyKey(),
                      instruction.batchId(),
                      instruction.channel(),
                      instruction.amount(),
                      clock.instant())));
      return ProcessingStatus.ROUTING;
    } catch (DataIntegrityViolationException duplicateKey) {
      // The insert rolled back. Read the existing row in a fresh transaction.
      return transactions.execute(tx -> resumeExisting(instruction, duplicateKey));
    }
  }

  private ProcessingStatus resumeExisting(
      PaymentInstruction instruction, DataIntegrityViolationException insertFailure) {
    // No row means the insert failed for another reason than a duplicate key: rethrow it.
    ProcessedInstruction existing =
        ledger.findById(instruction.idempotencyKey()).orElseThrow(() -> insertFailure);
    if (existing.getStatus() == ProcessingStatus.ROUTING) {
      // A previous attempt stopped mid-way (failure or crash). The row is managed, so the new
      // attempt count is written when this transaction commits.
      existing.recordAttempt(clock.instant());
      log.info("Resuming an unfinished instruction, attempt {}", existing.getAttempts());
    }
    return existing.getStatus();
  }

  private void finish(String idempotencyKey, ProcessingStatus outcome) {
    ProcessedInstruction row =
        ledger
            .findById(idempotencyKey)
            .orElseThrow(() -> new IllegalStateException("Claimed row disappeared"));
    row.finish(outcome, clock.instant());
  }
}
