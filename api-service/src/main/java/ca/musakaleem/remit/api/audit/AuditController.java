package ca.musakaleem.remit.api.audit;

import ca.musakaleem.remit.api.web.PageResponse;
import ca.musakaleem.remit.api.web.Requests;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Set;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/v1/audit-events}: the audit trail, ADMIN only (FR-SEC-03). */
@RestController
@RequestMapping("/api/v1/audit-events")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Audit", description = "The append-only audit trail")
public class AuditController {

  private static final Set<String> SORT = Set.of("occurredAt", "eventType", "entityId");

  private final AuditEventStore store;

  /**
   * Creates the controller.
   *
   * @param store audit storage
   */
  public AuditController(AuditEventStore store) {
    this.store = store;
  }

  /**
   * Lists audit documents, newest first by default.
   *
   * @param entityId batch id, idempotency key, username or dead letter id
   * @param eventType event type
   * @param from earliest occurredAt, inclusive, ISO-8601
   * @param to latest occurredAt, exclusive, ISO-8601
   * @param pageable {@code page}, {@code size} (at most 100) and {@code sort}
   * @return the page
   */
  @GetMapping
  @Operation(summary = "List audit events, newest first")
  public PageResponse<AuditEventView> list(
      @RequestParam(required = false) @Size(max = 100) String entityId,
      @RequestParam(required = false) AuditEventType eventType,
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          @Parameter(example = "2026-10-15T00:00:00Z")
          Instant from,
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          @Parameter(example = "2026-10-16T00:00:00Z")
          Instant to,
      @ParameterObject
          @PageableDefault(size = 20, sort = "occurredAt", direction = Sort.Direction.DESC)
          Pageable pageable) {
    Requests.range(from, to);
    return PageResponse.of(
        store.find(
            new AuditQuery(entityId, eventType, from, to), Requests.sortableBy(pageable, SORT)),
        AuditEventView::of);
  }
}
