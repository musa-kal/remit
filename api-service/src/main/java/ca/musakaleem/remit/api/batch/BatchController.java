package ca.musakaleem.remit.api.batch;

import ca.musakaleem.remit.api.instruction.InstructionView;
import ca.musakaleem.remit.api.rejection.RejectionView;
import ca.musakaleem.remit.api.web.PageResponse;
import ca.musakaleem.remit.api.web.Requests;
import ca.musakaleem.remit.common.event.BatchStatus;
import ca.musakaleem.remit.common.event.Channel;
import ca.musakaleem.remit.common.event.InstructionStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Batch endpoints of spec 7.3, readable by OPERATOR. Account numbers are always masked here. */
@RestController
@RequestMapping("/api/v1/batches")
@PreAuthorize("hasRole('OPERATOR')")
@Tag(name = "Batches", description = "Batch files, their instructions and rejected rows")
public class BatchController {

  private static final Set<String> BATCH_SORT =
      Set.of("receivedAt", "completedAt", "clientId", "status", "fileName");
  private static final Set<String> INSTRUCTION_SORT =
      Set.of("instructionRef", "amount", "status", "channel", "updatedAt", "idempotencyKey");
  private static final Set<String> REJECTION_SORT = Set.of("lineNumber", "occurredAt");

  private final BatchQueryService queries;

  /**
   * Creates the controller.
   *
   * @param queries read side
   */
  public BatchController(BatchQueryService queries) {
    this.queries = queries;
  }

  /**
   * Lists batches, newest first by default.
   *
   * @param status status filter
   * @param clientId client filter
   * @param from earliest receivedAt, inclusive, ISO-8601
   * @param to latest receivedAt, exclusive, ISO-8601
   * @param pageable {@code page}, {@code size} (at most 100) and {@code sort}
   * @return the page
   */
  @GetMapping
  @Operation(summary = "List batches, newest first")
  public PageResponse<BatchView> list(
      @RequestParam(required = false) BatchStatus status,
      @RequestParam(required = false)
          @Pattern(
              regexp = "[A-Z0-9]{3,10}",
              message = "must be 3 to 10 upper-case letters or digits")
          String clientId,
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          @Parameter(example = "2026-10-15T00:00:00Z")
          Instant from,
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          @Parameter(example = "2026-10-16T00:00:00Z")
          Instant to,
      @ParameterObject
          @PageableDefault(size = 20, sort = "receivedAt", direction = Sort.Direction.DESC)
          Pageable pageable) {
    Requests.range(from, to);
    return queries.list(status, clientId, from, to, Requests.sortableBy(pageable, BATCH_SORT));
  }

  /**
   * One batch with counts and control total.
   *
   * @param batchId batch id
   * @return the batch
   */
  @GetMapping("/{batchId}")
  @Operation(summary = "Batch detail with counts and control total")
  public BatchDetailView get(@PathVariable UUID batchId) {
    return queries.get(batchId);
  }

  /**
   * The instructions of a batch.
   *
   * @param batchId batch id
   * @param status status filter
   * @param channel channel filter
   * @param pageable {@code page}, {@code size} (at most 100) and {@code sort}
   * @return the page
   */
  @GetMapping("/{batchId}/instructions")
  @Operation(summary = "Instructions of a batch, account numbers masked")
  public PageResponse<InstructionView> instructions(
      @PathVariable UUID batchId,
      @RequestParam(required = false) InstructionStatus status,
      @RequestParam(required = false) Channel channel,
      @ParameterObject @PageableDefault(size = 20, sort = "instructionRef") Pageable pageable) {
    return queries.instructions(
        batchId, status, channel, Requests.sortableBy(pageable, INSTRUCTION_SORT));
  }

  /**
   * The rejected rows of a batch.
   *
   * @param batchId batch id
   * @param pageable {@code page}, {@code size} (at most 100) and {@code sort}
   * @return the page
   */
  @GetMapping("/{batchId}/rejections")
  @Operation(summary = "Rejected rows of a batch with their reason codes")
  public PageResponse<RejectionView> rejections(
      @PathVariable UUID batchId,
      @ParameterObject @PageableDefault(size = 20, sort = "lineNumber") Pageable pageable) {
    return queries.rejections(batchId, Requests.sortableBy(pageable, REJECTION_SORT));
  }
}
