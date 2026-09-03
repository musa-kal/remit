package ca.musakaleem.remit.intake.batch;

import ca.musakaleem.remit.common.MaskingUtil;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import ca.musakaleem.remit.intake.logging.MdcKeys;
import ca.musakaleem.remit.intake.publish.IntakeEventPublisher;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;

/**
 * Publishes a chunk of instructions to {@code payments.instructions} and waits until Kafka has
 * acknowledged all of them before the chunk commits. If any send fails the chunk rolls back and a
 * restart republishes it; routing-service deduplicates by idempotency key.
 */
public class InstructionKafkaWriter implements ItemWriter<PaymentInstruction> {

  private static final Logger log = LoggerFactory.getLogger(InstructionKafkaWriter.class);

  private final IntakeEventPublisher publisher;

  /**
   * Creates the writer.
   *
   * @param publisher event publisher
   */
  public InstructionKafkaWriter(IntakeEventPublisher publisher) {
    this.publisher = publisher;
  }

  @Override
  public void write(Chunk<? extends PaymentInstruction> chunk) {
    List<CompletableFuture<?>> sends = new ArrayList<>(chunk.size());
    for (PaymentInstruction instruction : chunk) {
      MDC.put(MdcKeys.IDEMPOTENCY_KEY, instruction.idempotencyKey());
      try {
        log.debug(
            "Publishing {} {} {} from debtor account {}",
            instruction.instructionRef(),
            instruction.channel(),
            instruction.amount(),
            MaskingUtil.maskAccount(instruction.debtor().account()));
        sends.add(publisher.publishInstruction(instruction));
      } finally {
        MDC.remove(MdcKeys.IDEMPOTENCY_KEY);
      }
    }
    publisher.awaitAll(sends);
    log.debug("Published chunk of {} instructions", sends.size());
  }
}
