package ca.musakaleem.remit.api.support;

import ca.musakaleem.remit.common.RemitJson;
import ca.musakaleem.remit.common.event.InstructionStatus;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/**
 * Inserts read model rows directly with SQL, for tests of the query endpoints that must not wait
 * for consumers.
 */
public final class Fixtures {

  private final JdbcTemplate jdbc;

  /**
   * Creates the helper.
   *
   * @param jdbc template on the test database
   */
  public Fixtures(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Inserts a batch.
   *
   * @param batchId batch id
   * @param clientId client
   * @param status status name
   * @param receivedAt received time
   */
  public void batch(UUID batchId, String clientId, String status, Instant receivedAt) {
    jdbc.update(
        "INSERT INTO payment_batch (batch_id, client_id, file_name, file_hash, status, total_rows,"
            + " accepted_rows, rejected_rows, control_total, received_at, completed_at, updated_at)"
            + " VALUES (?, ?, ?, ?, ?, 10, 8, 2, 12500.00, ?, ?, ?)",
        batchId.toString(),
        clientId,
        "PAY_" + clientId + "_20261015093000.csv",
        "c".repeat(64),
        status,
        Timestamp.from(receivedAt),
        Timestamp.from(receivedAt.plusSeconds(5)),
        Timestamp.from(receivedAt.plusSeconds(5)));
  }

  /**
   * Inserts an instruction with full details.
   *
   * @param instruction the instruction
   * @param status status
   */
  public void instruction(PaymentInstruction instruction, InstructionStatus status) {
    jdbc.update(
        "INSERT INTO payment_instruction (idempotency_key, batch_id, instruction_ref, client_id,"
            + " debtor_name, debtor_institution, debtor_transit, debtor_account, creditor_name,"
            + " creditor_institution, creditor_transit, creditor_account, amount, currency,"
            + " channel, value_date, remittance_info, status, replay_count, updated_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(3))",
        instruction.idempotencyKey(),
        instruction.batchId().toString(),
        instruction.instructionRef(),
        instruction.clientId(),
        instruction.debtor().name(),
        instruction.debtor().institution(),
        instruction.debtor().transit(),
        instruction.debtor().account(),
        instruction.creditor().name(),
        instruction.creditor().institution(),
        instruction.creditor().transit(),
        instruction.creditor().account(),
        instruction.amount(),
        instruction.currency(),
        instruction.channel().name(),
        java.sql.Date.valueOf(instruction.valueDate()),
        instruction.remittanceInfo(),
        status.name(),
        instruction.replayCount());
  }

  /**
   * Inserts a rejected row.
   *
   * @param batchId batch
   * @param lineNumber line
   */
  public void rejection(UUID batchId, long lineNumber) {
    jdbc.update(
        "INSERT INTO instruction_rejection (batch_id, client_id, line_number, instruction_ref,"
            + " reason_codes, raw_line_masked, occurred_at) VALUES (?, 'TEST01', ?, ?,"
            + " 'INVALID_ACCOUNT', 'ACME-0103,Acme,001,00011,***1234', NOW(3))",
        batchId.toString(),
        lineNumber,
        "REF-" + lineNumber);
  }

  /**
   * Inserts a pending, replayable dead letter for an instruction.
   *
   * @param instruction the instruction
   * @return the dead letter id
   */
  public long deadLetter(PaymentInstruction instruction) {
    String payload;
    try {
      payload = RemitJson.objectMapper().writeValueAsString(instruction);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
    KeyHolder key = new GeneratedKeyHolder();
    long offset = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L);
    jdbc.update(
        connection -> {
          var statement =
              connection.prepareStatement(
                  "INSERT INTO dead_letter (idempotency_key, batch_id, original_topic,"
                      + " original_partition, original_offset, exception_class,"
                      + " exception_message, payload, replay_count, status, first_seen_at,"
                      + " updated_at) VALUES (?, ?, 'payments.instructions', 0, ?,"
                      + " 'ca.musakaleem.remit.routing.error.RoutingException', 'Forced failure',"
                      + " ?, ?, 'PENDING', NOW(3), NOW(3))",
                  java.sql.Statement.RETURN_GENERATED_KEYS);
          statement.setString(1, instruction.idempotencyKey());
          statement.setString(2, instruction.batchId().toString());
          statement.setLong(3, offset);
          statement.setString(4, payload);
          statement.setInt(5, instruction.replayCount());
          return statement;
        },
        key);
    return key.getKey().longValue();
  }
}
