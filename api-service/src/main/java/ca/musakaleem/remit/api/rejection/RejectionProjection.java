package ca.musakaleem.remit.api.rejection;

import ca.musakaleem.remit.common.event.InstructionRejected;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Inserts rejected rows from {@code payments.rejected}, once per batch and line (FR-API-01). */
@Service
public class RejectionProjection {

  private final InstructionRejectionRepository rejections;

  /**
   * Creates the projection.
   *
   * @param rejections rejection repository
   */
  public RejectionProjection(InstructionRejectionRepository rejections) {
    this.rejections = rejections;
  }

  /**
   * Stores a rejected row unless it is stored already.
   *
   * @param event the event
   * @param occurredAt the event's {@code occurredAt}
   * @return true when stored now, false for a redelivery
   */
  @Transactional
  public boolean apply(InstructionRejected event, Instant occurredAt) {
    if (rejections.existsByBatchIdAndLineNumber(event.batchId().toString(), event.lineNumber())) {
      return false;
    }
    rejections.save(new InstructionRejection(event, occurredAt));
    return true;
  }
}
