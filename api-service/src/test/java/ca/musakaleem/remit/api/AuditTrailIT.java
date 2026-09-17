package ca.musakaleem.remit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.musakaleem.remit.api.audit.AuditEntityType;
import ca.musakaleem.remit.api.audit.AuditEvent;
import ca.musakaleem.remit.api.audit.AuditEventStore;
import ca.musakaleem.remit.api.audit.AuditEventType;
import ca.musakaleem.remit.api.audit.AuditQuery;
import ca.musakaleem.remit.api.support.AbstractApiIT;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/** The MongoDB audit store: append-only, idempotent by eventId, filterable over the API. */
class AuditTrailIT extends AbstractApiIT {

  @Autowired AuditEventStore store;

  @Test
  void insertingTheSameEventIdTwiceIsANoOp() {
    String entityId = "entity-" + UUID.randomUUID();
    AuditEvent event = event(UUID.randomUUID().toString(), entityId, Instant.now());

    assertThat(store.insert(event)).isTrue();
    assertThat(store.insert(event)).isFalse();

    assertThat(auditEvents(entityId))
        .singleElement()
        .satisfies(
            stored -> {
              assertThat(stored.id()).isNotBlank();
              assertThat(stored.eventId()).isEqualTo(event.eventId());
              assertThat(stored.details()).containsEntry("note", "first");
            });
  }

  @Test
  void theCollectionHasTheUniqueEventIdIndex() {
    assertThat(mongo.getCollection(AuditEvent.COLLECTION).listIndexes())
        .map(index -> index.get("name", String.class))
        .contains("ux_audit_event_id", "ix_audit_entity_occurred", "ix_audit_type_occurred");
    Document unique =
        mongo
            .getCollection(AuditEvent.COLLECTION)
            .listIndexes()
            .into(new java.util.ArrayList<>())
            .stream()
            .filter(index -> "ux_audit_event_id".equals(index.get("name")))
            .findFirst()
            .orElseThrow();
    assertThat(unique.getBoolean("unique")).isTrue();
  }

  @Test
  void findsByEntityTypeAndTimeRangeNewestFirst() {
    String entityId = "entity-" + UUID.randomUUID();
    Instant t0 = Instant.parse("2026-10-15T12:00:00Z");
    store.insert(event(UUID.randomUUID().toString(), entityId, t0));
    store.insert(event(UUID.randomUUID().toString(), entityId, t0.plusSeconds(60)));
    store.insert(event(UUID.randomUUID().toString(), entityId, t0.plusSeconds(120)));

    var all = store.find(new AuditQuery(entityId, null, null, null), PageRequest.of(0, 10));
    var window =
        store.find(
            new AuditQuery(
                entityId, AuditEventType.LOGIN_FAILURE, t0.plusSeconds(30), t0.plusSeconds(120)),
            PageRequest.of(0, 10));

    assertThat(all.getTotalElements()).isEqualTo(3);
    assertThat(all.getContent().get(0).occurredAt()).isEqualTo(t0.plusSeconds(120));
    assertThat(window.getTotalElements()).isEqualTo(1);
    assertThat(window.getContent().get(0).occurredAt()).isEqualTo(t0.plusSeconds(60));
  }

  @Test
  void adminsReadTheTrailOverTheApiWithFiltersAndPaging() throws Exception {
    String entityId = "entity-" + UUID.randomUUID();
    Instant t0 = Instant.parse("2026-10-15T12:00:00Z");
    for (int i = 0; i < 3; i++) {
      store.insert(event(UUID.randomUUID().toString(), entityId, t0.plusSeconds(i)));
    }

    mvc.perform(
            get("/api/v1/audit-events")
                .param("entityId", entityId)
                .param("eventType", "LOGIN_FAILURE")
                .param("size", "2")
                .header("Authorization", admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(3))
        .andExpect(jsonPath("$.totalPages").value(2))
        .andExpect(jsonPath("$.content[0].occurredAt").value("2026-10-15T12:00:02Z"))
        .andExpect(jsonPath("$.content[0].entityType").value("USER"))
        .andExpect(jsonPath("$.content[0].details.debtorAccount").value("******1234"));
    mvc.perform(
            get("/api/v1/audit-events")
                .param("eventType", "NOT_A_TYPE")
                .header("Authorization", admin()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("eventType"));
    mvc.perform(get("/api/v1/audit-events").param("sort", "actor").header("Authorization", admin()))
        .andExpect(status().isBadRequest());
  }

  private static AuditEvent event(String eventId, String entityId, Instant occurredAt) {
    return new AuditEvent(
        null,
        eventId,
        AuditEventType.LOGIN_FAILURE,
        AuditEntityType.USER,
        entityId,
        "user:" + entityId,
        occurredAt,
        null,
        Map.of("note", "first", "debtorAccount", "******1234"));
  }
}
