package ca.musakaleem.remit.api.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AuditWriterTest {

  private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

  private final AuditEventStore store = mock(AuditEventStore.class);
  private final AuditWriter writer = new AuditWriter(store, Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void aConsumedEventKeepsItsEventIdAndTheSystemActor() {
    when(store.insert(any())).thenReturn(true);
    UUID eventId = UUID.randomUUID();
    Instant occurredAt = NOW.minusSeconds(5);

    boolean written =
        writer.recordEvent(
            eventId,
            AuditEventType.BATCH_RECEIVED,
            AuditEntityType.BATCH,
            "batch-1",
            occurredAt,
            "batch-1",
            Map.of("status", "RECEIVED"));

    AuditEvent event = captured();
    assertThat(written).isTrue();
    assertThat(event.id()).isNull();
    assertThat(event.eventId()).isEqualTo(eventId.toString());
    assertThat(event.eventType()).isEqualTo(AuditEventType.BATCH_RECEIVED);
    assertThat(event.entityType()).isEqualTo(AuditEntityType.BATCH);
    assertThat(event.entityId()).isEqualTo("batch-1");
    assertThat(event.actor()).isEqualTo("system:api-service");
    assertThat(event.occurredAt()).isEqualTo(occurredAt);
    assertThat(event.correlationId()).isEqualTo("batch-1");
    assertThat(event.details()).containsEntry("status", "RECEIVED");
  }

  @Test
  void anAlreadyRecordedEventIsReportedAsNotWritten() {
    when(store.insert(any())).thenReturn(false);

    assertThat(
            writer.recordEvent(
                UUID.randomUUID(),
                AuditEventType.BATCH_RECEIVED,
                AuditEntityType.BATCH,
                "b",
                NOW,
                null,
                null))
        .isFalse();
  }

  @Test
  void aUserActionGetsAFreshIdTheUserActorAndTheCurrentTime() {
    writer.recordUserAction(
        "admin", AuditEventType.LOGIN_SUCCESS, AuditEntityType.USER, "admin", null, Map.of());

    AuditEvent event = captured();
    assertThat(UUID.fromString(event.eventId())).isNotNull();
    assertThat(event.actor()).isEqualTo("user:admin");
    assertThat(event.occurredAt()).isEqualTo(NOW);
    assertThat(event.details()).isEmpty();
  }

  @Test
  void detailsAreMaskedAndFlattenedToPlainValues() {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("debtorAccount", "1000001234");
    details.put("creditorAccountNumber", 2000005678L);
    details.put("rawLine", "ACME-0001,Acme,001,00011,1000001,Northwind,002,00022,2000001,10.00");
    details.put("amount", new BigDecimal("1500000.00"));
    details.put("occurredAt", NOW);
    details.put("status", AuditEventType.LOGIN_FAILURE);
    details.put("replayCount", 2);
    details.put("reasons", List.of("INVALID_ACCOUNT", "account 1234567 bad"));
    details.put("party", Map.of("account", "3000009999"));
    details.put("missing", null);

    Map<String, Object> safe = AuditDetails.sanitize(details);

    assertThat(safe)
        .containsEntry("debtorAccount", "******1234")
        .containsEntry("creditorAccountNumber", "******5678")
        .containsEntry(
            "rawLine", "ACME-0001,Acme,001,00011,***0001,Northwind,002,00022,***0001,10.00")
        .containsEntry("amount", "1500000.00")
        .containsEntry("occurredAt", "2026-10-15T12:00:00Z")
        .containsEntry("status", "LOGIN_FAILURE")
        .containsEntry("replayCount", 2)
        .containsEntry("reasons", List.of("INVALID_ACCOUNT", "account ***4567 bad"))
        .containsEntry("party", Map.of("account", "******9999"))
        .doesNotContainKey("missing");
    assertThat(AuditDetails.sanitize(null)).isEmpty();
  }

  private AuditEvent captured() {
    ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
    verify(store).insert(event.capture());
    return event.getValue();
  }
}
