package ca.musakaleem.remit.api.rejection;

import ca.musakaleem.remit.common.MaskingUtil;
import ca.musakaleem.remit.common.event.InstructionRejected;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A file row rejected by intake-service, in {@code remit_api.instruction_rejection}. The natural
 * key is batch id plus line number; the raw line is stored masked.
 */
@Entity
@Table(name = "instruction_rejection")
public class InstructionRejection {

  private static final String SEPARATOR = ",";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "batch_id", nullable = false, length = 36, updatable = false)
  private String batchId;

  @Column(name = "client_id", length = 10, updatable = false)
  private String clientId;

  @Column(name = "line_number", nullable = false, updatable = false)
  private long lineNumber;

  @Column(name = "instruction_ref", length = 100, updatable = false)
  private String instructionRef;

  @Column(name = "reason_codes", nullable = false, length = 500, updatable = false)
  private String reasonCodes;

  @Column(name = "raw_line_masked", columnDefinition = "text", updatable = false)
  private String rawLineMasked;

  @Column(name = "occurred_at", nullable = false, updatable = false)
  private Instant occurredAt;

  /** For JPA only. */
  protected InstructionRejection() {}

  /**
   * Creates the row from a rejection event. The raw line is masked again as a safety net, even
   * though intake-service masks it before publishing.
   *
   * @param event the event
   * @param occurredAt the event's {@code occurredAt} header
   */
  public InstructionRejection(InstructionRejected event, Instant occurredAt) {
    this.batchId = Objects.requireNonNull(event.batchId(), "batchId").toString();
    this.clientId = event.clientId();
    this.lineNumber = event.lineNumber();
    this.instructionRef = cut(event.instructionRef(), 100);
    List<String> codes = event.reasonCodes() == null ? List.of() : event.reasonCodes();
    this.reasonCodes = cut(String.join(SEPARATOR, codes), 500);
    this.rawLineMasked = MaskingUtil.maskAccountNumbers(event.rawLine());
    this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
  }

  private static String cut(String value, int max) {
    return value == null || value.length() <= max ? value : value.substring(0, max);
  }

  public Long getId() {
    return id;
  }

  public UUID getBatchId() {
    return UUID.fromString(batchId);
  }

  public String getClientId() {
    return clientId;
  }

  public long getLineNumber() {
    return lineNumber;
  }

  public String getInstructionRef() {
    return instructionRef;
  }

  /**
   * The reason codes, in the order intake-service reported them.
   *
   * @return the codes
   */
  public List<String> getReasonCodes() {
    return reasonCodes.isEmpty() ? List.of() : Arrays.asList(reasonCodes.split(SEPARATOR));
  }

  public String getRawLineMasked() {
    return rawLineMasked;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }
}
