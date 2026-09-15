package ca.musakaleem.remit.api.stats;

import ca.musakaleem.remit.api.batch.PaymentBatchRepository;
import ca.musakaleem.remit.api.deadletter.DeadLetterRepository;
import ca.musakaleem.remit.api.deadletter.DeadLetterStatus;
import ca.musakaleem.remit.api.instruction.InstructionRecordRepository;
import ca.musakaleem.remit.api.instruction.InstructionStatusHistoryRepository;
import ca.musakaleem.remit.api.rejection.InstructionRejectionRepository;
import ca.musakaleem.remit.api.web.Requests;
import ca.musakaleem.remit.api.web.StatusCounts;
import ca.musakaleem.remit.common.event.BatchStatus;
import ca.musakaleem.remit.common.event.InstructionStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/v1/stats/summary}: counts per status for a date range, OPERATOR. */
@RestController
@RequestMapping("/api/v1/stats")
@PreAuthorize("hasRole('OPERATOR')")
@Tag(name = "Stats", description = "Counts by status")
public class StatsController {

  private final PaymentBatchRepository batches;
  private final InstructionRecordRepository instructions;
  private final InstructionStatusHistoryRepository history;
  private final InstructionRejectionRepository rejections;
  private final DeadLetterRepository deadLetters;

  /**
   * Creates the controller.
   *
   * @param batches batch repository
   * @param instructions instruction repository
   * @param history status history repository
   * @param rejections rejection repository
   * @param deadLetters dead letter repository
   */
  public StatsController(
      PaymentBatchRepository batches,
      InstructionRecordRepository instructions,
      InstructionStatusHistoryRepository history,
      InstructionRejectionRepository rejections,
      DeadLetterRepository deadLetters) {
    this.batches = batches;
    this.instructions = instructions;
    this.history = history;
    this.rejections = rejections;
    this.deadLetters = deadLetters;
  }

  /**
   * Counts by status.
   *
   * @param from start, inclusive, ISO-8601; omit for the beginning
   * @param to end, exclusive, ISO-8601; omit for now
   * @return the summary
   */
  @GetMapping("/summary")
  @Transactional(readOnly = true)
  @Operation(summary = "Counts by batch status and instruction status for a date range")
  public StatsSummary summary(
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          @Parameter(example = "2026-10-15T00:00:00Z")
          Instant from,
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          @Parameter(example = "2026-10-16T00:00:00Z")
          Instant to) {
    Requests.range(from, to);
    return new StatsSummary(
        from,
        to,
        StatusCounts.of(BatchStatus.class, batches.countByStatus(from, to)),
        StatusCounts.of(InstructionStatus.class, instructions.countByStatus(from, to)),
        history.countByStatusBetween(InstructionStatus.DUPLICATE, from, to),
        rejections.countBetween(from, to),
        StatusCounts.of(DeadLetterStatus.class, deadLetters.countByStatus(from, to)));
  }
}
