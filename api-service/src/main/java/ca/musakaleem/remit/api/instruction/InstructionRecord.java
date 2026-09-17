package ca.musakaleem.remit.api.instruction;

import ca.musakaleem.remit.api.messaging.EventTime;
import ca.musakaleem.remit.common.event.Channel;
import ca.musakaleem.remit.common.event.InstructionStatus;
import ca.musakaleem.remit.common.event.Party;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One instruction in {@code remit_api.payment_instruction}. Its status comes from status events and
 * dead-letter records; its payment details from the routed record or the dead-letter payload,
 * whichever arrives first.
 *
 * <p>Ordering rule: {@code updatedAt} is the {@code occurredAt} of the last applied status. A
 * status that happened strictly before it is ignored; an equal time is applied, because
 * routing-service publishes REPLAYED and then ROUTED within the same millisecond. DUPLICATE is
 * history only: a second delivery never downgrades a routed instruction.
 */
@Entity
@Table(name = "payment_instruction")
public class InstructionRecord {

  /** Longest status detail stored; routing-service already cuts details to this length. */
  public static final int MAX_DETAIL_LENGTH = 500;

  @Id
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "idempotency_key", nullable = false, length = 64, updatable = false)
  private String idempotencyKey;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "batch_id", nullable = false, length = 36)
  private String batchId;

  @Column(name = "instruction_ref", length = 35)
  private String instructionRef;

  @Column(name = "client_id", length = 10)
  private String clientId;

  @Column(name = "debtor_name", length = 70)
  private String debtorName;

  @Column(name = "debtor_institution", length = 3)
  private String debtorInstitution;

  @Column(name = "debtor_transit", length = 5)
  private String debtorTransit;

  @Column(name = "debtor_account", length = 12)
  private String debtorAccount;

  @Column(name = "creditor_name", length = 70)
  private String creditorName;

  @Column(name = "creditor_institution", length = 3)
  private String creditorInstitution;

  @Column(name = "creditor_transit", length = 5)
  private String creditorTransit;

  @Column(name = "creditor_account", length = 12)
  private String creditorAccount;

  @Column(name = "amount", precision = 15, scale = 2)
  private BigDecimal amount;

  @Column(name = "currency", length = 3)
  private String currency;

  @Enumerated(EnumType.STRING)
  @Column(name = "channel", length = 10)
  private Channel channel;

  @Column(name = "value_date")
  private LocalDate valueDate;

  @Column(name = "remittance_info", length = 140)
  private String remittanceInfo;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private InstructionStatus status;

  @Column(name = "status_detail", length = MAX_DETAIL_LENGTH)
  private String statusDetail;

  @Column(name = "replay_count", nullable = false)
  private int replayCount;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /** For JPA only. */
  protected InstructionRecord() {}

  /**
   * Creates a row from its first status.
   *
   * @param idempotencyKey instruction key
   * @param batchId batch the instruction arrived in
   * @param status first known status
   * @param channel channel, may be {@code null}
   * @param detail status detail, may be {@code null}
   * @param occurredAt when the status happened
   */
  public InstructionRecord(
      String idempotencyKey,
      UUID batchId,
      InstructionStatus status,
      Channel channel,
      String detail,
      Instant occurredAt) {
    this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    this.batchId = Objects.requireNonNull(batchId, "batchId").toString();
    this.status = Objects.requireNonNull(status, "status");
    this.channel = channel;
    this.statusDetail = cut(detail);
    this.updatedAt = EventTime.millis(Objects.requireNonNull(occurredAt, "occurredAt"));
  }

  /**
   * Applies a status unless it is older than the current one or a DUPLICATE.
   *
   * @param newStatus the status
   * @param newChannel channel named by the event, may be {@code null}
   * @param detail detail, may be {@code null}
   * @param occurredAt when it happened
   * @return true when the current status changed
   */
  public boolean applyStatus(
      InstructionStatus newStatus, Channel newChannel, String detail, Instant occurredAt) {
    Instant at = EventTime.millis(occurredAt);
    if (at.isBefore(updatedAt)) {
      return false;
    }
    if (newStatus == InstructionStatus.DUPLICATE && status != InstructionStatus.DUPLICATE) {
      return false;
    }
    this.status = newStatus;
    this.statusDetail = cut(detail);
    if (newChannel != null) {
      this.channel = newChannel;
    }
    this.updatedAt = at;
    return true;
  }

  /**
   * Copies the payment details of the instruction. Details never change for a key, except the
   * replay count, which only grows.
   *
   * @param instruction the instruction as routed or dead-lettered
   */
  public void applyDetails(PaymentInstruction instruction) {
    this.instructionRef = instruction.instructionRef();
    this.clientId = instruction.clientId();
    Party debtor = instruction.debtor();
    if (debtor != null) {
      this.debtorName = debtor.name();
      this.debtorInstitution = debtor.institution();
      this.debtorTransit = debtor.transit();
      this.debtorAccount = debtor.account();
    }
    Party creditor = instruction.creditor();
    if (creditor != null) {
      this.creditorName = creditor.name();
      this.creditorInstitution = creditor.institution();
      this.creditorTransit = creditor.transit();
      this.creditorAccount = creditor.account();
    }
    this.amount = instruction.amount();
    this.currency = instruction.currency();
    if (instruction.channel() != null) {
      this.channel = instruction.channel();
    }
    this.valueDate = instruction.valueDate();
    this.remittanceInfo = instruction.remittanceInfo();
    this.replayCount = Math.max(replayCount, instruction.replayCount());
  }

  private static String cut(String detail) {
    return detail == null || detail.length() <= MAX_DETAIL_LENGTH
        ? detail
        : detail.substring(0, MAX_DETAIL_LENGTH);
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public UUID getBatchId() {
    return UUID.fromString(batchId);
  }

  public String getInstructionRef() {
    return instructionRef;
  }

  public String getClientId() {
    return clientId;
  }

  /**
   * The debtor, or {@code null} while the details are unknown.
   *
   * @return the debtor with its full account number
   */
  public Party getDebtor() {
    return debtorName == null && debtorAccount == null
        ? null
        : new Party(debtorName, debtorInstitution, debtorTransit, debtorAccount);
  }

  /**
   * The creditor, or {@code null} while the details are unknown.
   *
   * @return the creditor with its full account number
   */
  public Party getCreditor() {
    return creditorName == null && creditorAccount == null
        ? null
        : new Party(creditorName, creditorInstitution, creditorTransit, creditorAccount);
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public String getCurrency() {
    return currency;
  }

  public Channel getChannel() {
    return channel;
  }

  public LocalDate getValueDate() {
    return valueDate;
  }

  public String getRemittanceInfo() {
    return remittanceInfo;
  }

  public InstructionStatus getStatus() {
    return status;
  }

  public String getStatusDetail() {
    return statusDetail;
  }

  public int getReplayCount() {
    return replayCount;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
