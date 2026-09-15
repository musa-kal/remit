package ca.musakaleem.remit.api.audit;

import java.time.Instant;
import java.util.Map;

/**
 * An audit document in {@code GET /api/v1/audit-events}.
 *
 * @param id MongoDB id
 * @param eventId unique event id
 * @param eventType what happened
 * @param entityType what it happened to
 * @param entityId batch id, idempotency key, username or dead letter id
 * @param actor {@code system:api-service} or {@code user:<username>}
 * @param occurredAt when it happened
 * @param correlationId batch id, when there is one
 * @param details free-form facts, account numbers masked
 */
public record AuditEventView(
    String id,
    String eventId,
    AuditEventType eventType,
    AuditEntityType entityType,
    String entityId,
    String actor,
    Instant occurredAt,
    String correlationId,
    Map<String, Object> details) {

  /**
   * Maps a document.
   *
   * @param event the document
   * @return the view
   */
  public static AuditEventView of(AuditEvent event) {
    return new AuditEventView(
        event.id(),
        event.eventId(),
        event.eventType(),
        event.entityType(),
        event.entityId(),
        event.actor(),
        event.occurredAt(),
        event.correlationId(),
        event.details());
  }
}
