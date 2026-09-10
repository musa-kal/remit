package ca.musakaleem.remit.api.audit;

import java.time.Instant;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One immutable document in {@code remit_audit.audit_events} (spec 8.2). The unique index on {@code
 * eventId} makes writing the same event twice a no-op.
 *
 * @param id MongoDB ObjectId, assigned on insert
 * @param eventId unique id: the Kafka {@code eventId} header of a consumed event, or a random UUID
 *     for an operator action
 * @param eventType what happened
 * @param entityType what it happened to
 * @param entityId batch id, idempotency key, username or dead letter id
 * @param actor {@code system:api-service} or {@code user:<username>}
 * @param occurredAt when it happened (UTC)
 * @param correlationId batch id, when there is one
 * @param details free-form facts, account numbers masked
 */
@Document(collection = AuditEvent.COLLECTION)
public record AuditEvent(
    @Id String id,
    String eventId,
    AuditEventType eventType,
    AuditEntityType entityType,
    String entityId,
    String actor,
    Instant occurredAt,
    String correlationId,
    Map<String, Object> details) {

  /** The collection name, created with its indexes by docker/mongo/init.js. */
  public static final String COLLECTION = "audit_events";
}
