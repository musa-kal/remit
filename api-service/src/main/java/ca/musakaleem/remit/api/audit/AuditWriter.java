package ca.musakaleem.remit.api.audit;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes one audit document per consumed event and per operator action (FR-API-02). Consumed events
 * reuse their Kafka {@code eventId}, so redelivery never creates a second document; operator
 * actions get a fresh id. Details are sanitized by {@link AuditDetails}.
 */
@Component
public class AuditWriter {

  /** Actor of every document written for a consumed event. */
  public static final String SYSTEM_ACTOR = "system:api-service";

  private static final String USER_ACTOR_PREFIX = "user:";

  private static final Logger log = LoggerFactory.getLogger(AuditWriter.class);

  private final AuditEventStore store;
  private final Clock clock;

  /**
   * Creates the writer.
   *
   * @param store append-only storage
   * @param clock clock for operator actions
   */
  public AuditWriter(AuditEventStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  /**
   * Records a consumed event, with actor {@value #SYSTEM_ACTOR}.
   *
   * @param eventId the event's id; the same id is stored only once
   * @param type what happened
   * @param entityType what it happened to
   * @param entityId which one
   * @param occurredAt when the event happened
   * @param correlationId batch id, may be {@code null}
   * @param details facts about the event
   * @return true when written, false when this event was already recorded
   */
  public boolean recordEvent(
      UUID eventId,
      AuditEventType type,
      AuditEntityType entityType,
      String entityId,
      Instant occurredAt,
      String correlationId,
      Map<String, ?> details) {
    return write(
        eventId, type, entityType, entityId, SYSTEM_ACTOR, occurredAt, correlationId, details);
  }

  /**
   * Records something an operator did, with actor {@code user:<username>} and the current time.
   *
   * @param username authenticated or attempted username
   * @param type what happened
   * @param entityType what it happened to
   * @param entityId which one
   * @param correlationId batch id, may be {@code null}
   * @param details facts about the action
   */
  public void recordUserAction(
      String username,
      AuditEventType type,
      AuditEntityType entityType,
      String entityId,
      String correlationId,
      Map<String, ?> details) {
    write(
        UUID.randomUUID(),
        type,
        entityType,
        entityId,
        USER_ACTOR_PREFIX + username,
        clock.instant(),
        correlationId,
        details);
  }

  private boolean write(
      UUID eventId,
      AuditEventType type,
      AuditEntityType entityType,
      String entityId,
      String actor,
      Instant occurredAt,
      String correlationId,
      Map<String, ?> details) {
    AuditEvent event =
        new AuditEvent(
            null,
            eventId.toString(),
            type,
            entityType,
            entityId,
            actor,
            occurredAt,
            correlationId,
            AuditDetails.sanitize(details));
    boolean written = store.insert(event);
    if (!written) {
      log.debug("Audit event {} ({}) was already recorded", eventId, type);
    }
    return written;
  }
}
