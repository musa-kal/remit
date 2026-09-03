package ca.musakaleem.remit.intake.batch;

import ca.musakaleem.remit.common.MaskingUtil;
import ca.musakaleem.remit.common.event.InstructionRejected;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import ca.musakaleem.remit.intake.publish.IntakeEventPublisher;
import ca.musakaleem.remit.intake.validation.InstructionValidationException;
import ca.musakaleem.remit.intake.validation.RawInstruction;
import ca.musakaleem.remit.intake.validation.ReasonCode;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.SkipListener;

/**
 * Publishes every skipped row to {@code payments.rejected} with its reason codes and a masked copy
 * of the raw line. Spring Batch calls it just before the chunk commits, so a rolled back chunk does
 * not publish its rejections twice.
 */
public class RejectedInstructionListener
    implements SkipListener<RawInstruction, PaymentInstruction> {

  private static final Logger log = LoggerFactory.getLogger(RejectedInstructionListener.class);

  private final IntakeEventPublisher publisher;
  private final UUID batchId;
  private final String clientId;

  /**
   * Creates the listener for one step execution.
   *
   * @param publisher event publisher
   * @param batchId batch id from the job context
   * @param clientId client id from the job parameters
   */
  public RejectedInstructionListener(
      IntakeEventPublisher publisher, UUID batchId, String clientId) {
    this.publisher = publisher;
    this.batchId = batchId;
    this.clientId = clientId;
  }

  @Override
  public void onSkipInProcess(RawInstruction row, Throwable t) {
    List<String> reasons =
        t instanceof InstructionValidationException invalid
            ? invalid.reasonCodes().stream().map(ReasonCode::name).toList()
            : List.of(ReasonCode.MALFORMED_ROW.name());
    String maskedLine = MaskingUtil.maskAccountNumbers(row.rawLine());
    publisher.publishRejection(
        new InstructionRejected(
            batchId, clientId, row.lineNumber(), row.instructionRef(), reasons, maskedLine));
    log.info("Rejected line {} {}: {}", row.lineNumber(), reasons, maskedLine);
  }
}
