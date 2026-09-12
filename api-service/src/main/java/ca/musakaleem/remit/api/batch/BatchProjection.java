package ca.musakaleem.remit.api.batch;

import ca.musakaleem.remit.common.event.BatchEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Upserts {@code payment_batch} from batch events (FR-API-01). */
@Service
public class BatchProjection {

  private final PaymentBatchRepository batches;

  /**
   * Creates the projection.
   *
   * @param batches batch repository
   */
  public BatchProjection(PaymentBatchRepository batches) {
    this.batches = batches;
  }

  /**
   * Inserts the batch or applies the event to it.
   *
   * @param event the event, with its {@code occurredAt} set
   * @param reasonCode the {@code reasonCode} header of a FAILED event, or {@code null}
   * @return true when the read model changed, false when the event was older and ignored
   */
  @Transactional
  public boolean apply(BatchEvent event, String reasonCode) {
    return batches
        .findById(event.batchId().toString())
        .map(existing -> existing.apply(event, reasonCode))
        .orElseGet(
            () -> {
              batches.save(PaymentBatch.from(event, reasonCode));
              return true;
            });
  }
}
