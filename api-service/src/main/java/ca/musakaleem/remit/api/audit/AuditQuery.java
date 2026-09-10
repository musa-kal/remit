package ca.musakaleem.remit.api.audit;

import java.time.Instant;

/**
 * Filters of {@code GET /api/v1/audit-events}; every field is optional.
 *
 * @param entityId exact entity id
 * @param eventType exact event type
 * @param from earliest {@code occurredAt}, inclusive
 * @param to latest {@code occurredAt}, exclusive
 */
public record AuditQuery(String entityId, AuditEventType eventType, Instant from, Instant to) {}
