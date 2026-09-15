package ca.musakaleem.remit.api.batch;

import ca.musakaleem.remit.api.deadletter.DeadLetterRepository;
import ca.musakaleem.remit.api.instruction.InstructionRecord;
import ca.musakaleem.remit.api.instruction.InstructionRecordRepository;
import ca.musakaleem.remit.api.instruction.InstructionView;
import ca.musakaleem.remit.api.rejection.InstructionRejectionRepository;
import ca.musakaleem.remit.api.rejection.RejectionView;
import ca.musakaleem.remit.api.web.NotFoundException;
import ca.musakaleem.remit.api.web.PageResponse;
import ca.musakaleem.remit.api.web.StatusCounts;
import ca.musakaleem.remit.common.event.BatchStatus;
import ca.musakaleem.remit.common.event.Channel;
import ca.musakaleem.remit.common.event.InstructionStatus;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of the batch endpoints. */
@Service
@Transactional(readOnly = true)
public class BatchQueryService {

  private final PaymentBatchRepository batches;
  private final InstructionRecordRepository instructions;
  private final InstructionRejectionRepository rejections;
  private final DeadLetterRepository deadLetters;

  /**
   * Creates the service.
   *
   * @param batches batch repository
   * @param instructions instruction repository
   * @param rejections rejection repository
   * @param deadLetters dead letter repository
   */
  public BatchQueryService(
      PaymentBatchRepository batches,
      InstructionRecordRepository instructions,
      InstructionRejectionRepository rejections,
      DeadLetterRepository deadLetters) {
    this.batches = batches;
    this.instructions = instructions;
    this.rejections = rejections;
    this.deadLetters = deadLetters;
  }

  /**
   * Lists batches.
   *
   * @param status status filter, may be {@code null}
   * @param clientId client filter, may be {@code null}
   * @param from earliest {@code receivedAt}, inclusive, may be {@code null}
   * @param to latest {@code receivedAt}, exclusive, may be {@code null}
   * @param pageable page, size and sort
   * @return the page
   */
  public PageResponse<BatchView> list(
      BatchStatus status, String clientId, Instant from, Instant to, Pageable pageable) {
    Specification<PaymentBatch> filter = (root, query, cb) -> cb.conjunction();
    if (status != null) {
      filter = filter.and((root, query, cb) -> cb.equal(root.get("status"), status));
    }
    if (clientId != null) {
      filter = filter.and((root, query, cb) -> cb.equal(root.get("clientId"), clientId));
    }
    if (from != null) {
      filter =
          filter.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("receivedAt"), from));
    }
    if (to != null) {
      filter = filter.and((root, query, cb) -> cb.lessThan(root.get("receivedAt"), to));
    }
    return PageResponse.of(batches.findAll(filter, pageable), BatchView::of);
  }

  /**
   * One batch with its counts.
   *
   * @param batchId batch id
   * @return the detail
   * @throws NotFoundException when unknown
   */
  public BatchDetailView get(UUID batchId) {
    PaymentBatch batch = require(batchId);
    String id = batchId.toString();
    return BatchDetailView.of(
        batch,
        StatusCounts.of(InstructionStatus.class, instructions.countByStatusForBatch(id)),
        rejections.countByBatchId(id),
        deadLetters.countByBatch(id));
  }

  /**
   * The instructions of a batch, account numbers masked.
   *
   * @param batchId batch id
   * @param status status filter, may be {@code null}
   * @param channel channel filter, may be {@code null}
   * @param pageable page, size and sort
   * @return the page
   * @throws NotFoundException when the batch is unknown
   */
  public PageResponse<InstructionView> instructions(
      UUID batchId, InstructionStatus status, Channel channel, Pageable pageable) {
    require(batchId);
    String id = batchId.toString();
    Specification<InstructionRecord> filter =
        (root, query, cb) -> cb.equal(root.get("batchId"), id);
    if (status != null) {
      filter = filter.and((root, query, cb) -> cb.equal(root.get("status"), status));
    }
    if (channel != null) {
      filter = filter.and((root, query, cb) -> cb.equal(root.get("channel"), channel));
    }
    return PageResponse.of(instructions.findAll(filter, pageable), InstructionView::masked);
  }

  /**
   * The rejected rows of a batch.
   *
   * @param batchId batch id
   * @param pageable page, size and sort
   * @return the page
   * @throws NotFoundException when the batch is unknown
   */
  public PageResponse<RejectionView> rejections(UUID batchId, Pageable pageable) {
    require(batchId);
    return PageResponse.of(
        rejections.findByBatchId(batchId.toString(), pageable), RejectionView::of);
  }

  private PaymentBatch require(UUID batchId) {
    return batches
        .findById(batchId.toString())
        .orElseThrow(() -> new NotFoundException("Batch " + batchId + " not found"));
  }
}
