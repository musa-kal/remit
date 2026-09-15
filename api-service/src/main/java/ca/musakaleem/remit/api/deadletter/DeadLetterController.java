package ca.musakaleem.remit.api.deadletter;

import ca.musakaleem.remit.api.audit.AuditEntityType;
import ca.musakaleem.remit.api.audit.AuditEventType;
import ca.musakaleem.remit.api.audit.AuditWriter;
import ca.musakaleem.remit.api.web.PageResponse;
import ca.musakaleem.remit.api.web.Requests;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dead-letter endpoints of spec 7.3: OPERATOR lists them, only ADMIN replays (FR-SEC-03). Every
 * accepted replay writes a DLT_REPLAY_REQUESTED audit document naming the administrator.
 */
@RestController
@RequestMapping("/api/v1/dead-letters")
@Tag(name = "Dead Letters", description = "Dead-lettered instructions and their replay")
public class DeadLetterController {

  private static final Set<String> SORT =
      Set.of("firstSeenAt", "updatedAt", "status", "replayCount", "id");

  private final DeadLetterRepository deadLetters;
  private final ReplayService replayService;
  private final AuditWriter audit;
  private final int maxReplays;

  /**
   * Creates the controller.
   *
   * @param deadLetters dead letter repository
   * @param replayService replay logic
   * @param audit audit trail
   * @param properties replay cap
   */
  public DeadLetterController(
      DeadLetterRepository deadLetters,
      ReplayService replayService,
      AuditWriter audit,
      ReplayProperties properties) {
    this.deadLetters = deadLetters;
    this.replayService = replayService;
    this.audit = audit;
    this.maxReplays = properties.maxReplays();
  }

  /**
   * Lists dead letters, newest first by default.
   *
   * @param status status filter
   * @param pageable {@code page}, {@code size} (at most 100) and {@code sort}
   * @return the page
   */
  @GetMapping
  @PreAuthorize("hasRole('OPERATOR')")
  @Transactional(readOnly = true)
  @Operation(summary = "List dead letters: key, exception, first seen, replay count, status")
  public PageResponse<DeadLetterView> list(
      @RequestParam(required = false) DeadLetterStatus status,
      @ParameterObject
          @PageableDefault(size = 20, sort = "firstSeenAt", direction = Sort.Direction.DESC)
          Pageable pageable) {
    Specification<DeadLetter> filter =
        status == null
            ? (root, query, cb) -> cb.conjunction()
            : (root, query, cb) -> cb.equal(root.get("status"), status);
    return PageResponse.of(
        deadLetters.findAll(filter, Requests.sortableBy(pageable, SORT)),
        deadLetter -> DeadLetterView.of(deadLetter, maxReplays));
  }

  /**
   * Replays a dead letter.
   *
   * @param id dead letter id
   * @param authentication the administrator
   * @return what was republished
   */
  @PostMapping("/{id}/replay")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.ACCEPTED)
  @Operation(summary = "Republish a dead letter to payments.instructions with replayCount + 1")
  @ApiResponse(responseCode = "202", description = "Replay published")
  @ApiResponse(responseCode = "404", description = "Unknown dead letter")
  @ApiResponse(
      responseCode = "409",
      description = "Replayed 3 times already, a replay is pending, or the record is unreadable")
  public ReplayResponse replay(@PathVariable long id, Authentication authentication) {
    DeadLetter deadLetter = replayService.replay(id);
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("deadLetterId", deadLetter.getId());
    details.put("replayCount", deadLetter.getReplayCount());
    details.put("maxReplays", maxReplays);
    audit.recordUserAction(
        authentication.getName(),
        AuditEventType.DLT_REPLAY_REQUESTED,
        AuditEntityType.INSTRUCTION,
        deadLetter.getIdempotencyKey(),
        deadLetter.getBatchId() == null ? null : deadLetter.getBatchId().toString(),
        details);
    return ReplayResponse.of(deadLetter);
  }
}
