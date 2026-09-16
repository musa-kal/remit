package ca.musakaleem.remit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Runs the Flyway migrations on a brand new, empty MySQL 8.4 schema (spec 10.2). */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FlywayMigrationIT {

  @Container
  static final MySQLContainer<?> EMPTY_MYSQL =
      new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
          .withDatabaseName("remit_api")
          .withUsername("remit_api")
          .withPassword("test-only");

  @Test
  @Order(1)
  void migratesAnEmptySchemaOnceAndValidates() throws SQLException {
    Flyway flyway = flyway();

    MigrateResult first = flyway.migrate();
    MigrateResult second = flyway.migrate();
    flyway.validate();

    assertThat(first.success).isTrue();
    assertThat(first.migrationsExecuted).isEqualTo(5);
    assertThat(second.migrationsExecuted).isZero();
    assertThat(tables())
        .contains(
            "app_user",
            "app_user_role",
            "payment_batch",
            "payment_instruction",
            "instruction_status_history",
            "instruction_rejection",
            "dead_letter");
    assertThat(columnType("payment_batch", "control_total")).isEqualTo("decimal(15,2)");
    assertThat(columnType("payment_instruction", "amount")).isEqualTo("decimal(15,2)");
    assertThat(columnType("dead_letter", "payload")).isEqualTo("json");
  }

  @Test
  @Order(2)
  void everyListFilterIsIndexed() throws SQLException {
    assertThat(indexedColumns("payment_batch")).contains("received_at", "status", "client_id");
    assertThat(indexedColumns("payment_instruction")).contains("batch_id", "status", "channel");
    assertThat(indexedColumns("instruction_rejection")).contains("batch_id", "line_number");
    assertThat(indexedColumns("dead_letter"))
        .contains("status", "first_seen_at", "idempotency_key");
    assertThat(indexedColumns("instruction_status_history"))
        .contains("idempotency_key", "event_id");
  }

  @Test
  @Order(3)
  void deadLettersAreUniqueByOriginalTopicPartitionAndOffset() throws SQLException {
    try (Connection connection = connect();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(deadLetter(42));

      assertThatThrownBy(() -> statement.executeUpdate(deadLetter(42)))
          .isInstanceOf(SQLIntegrityConstraintViolationException.class)
          .hasMessageContaining("ux_dead_letter_origin");
      statement.executeUpdate(deadLetter(43));
    }
  }

  @Test
  @Order(4)
  void usernamesAreUnique() throws SQLException {
    try (Connection connection = connect();
        Statement statement = connection.createStatement()) {
      String insert =
          "INSERT INTO app_user (username, password_hash, enabled, created_at)"
              + " VALUES ('someone', 'hash', TRUE, NOW(3))";
      statement.executeUpdate(insert);

      assertThatThrownBy(() -> statement.executeUpdate(insert))
          .isInstanceOf(SQLIntegrityConstraintViolationException.class);
    }
  }

  private static String deadLetter(long offset) {
    return "INSERT INTO dead_letter (original_topic, original_partition, original_offset, payload,"
        + " replay_count, status, first_seen_at, updated_at) VALUES ('payments.instructions', 1, "
        + offset
        + ", '{}', 0, 'PENDING', NOW(3), NOW(3))";
  }

  private static Flyway flyway() {
    return Flyway.configure()
        .dataSource(EMPTY_MYSQL.getJdbcUrl(), EMPTY_MYSQL.getUsername(), EMPTY_MYSQL.getPassword())
        .locations("classpath:db/migration")
        .load();
  }

  private static List<String> tables() throws SQLException {
    return strings(
        "SELECT table_name FROM information_schema.tables WHERE table_schema = 'remit_api'");
  }

  private static String columnType(String table, String column) throws SQLException {
    return strings(
            "SELECT column_type FROM information_schema.columns WHERE table_schema = 'remit_api'"
                + " AND table_name = '"
                + table
                + "' AND column_name = '"
                + column
                + "'")
        .get(0);
  }

  private static List<String> indexedColumns(String table) throws SQLException {
    return strings(
        "SELECT column_name FROM information_schema.statistics WHERE table_schema = 'remit_api'"
            + " AND table_name = '"
            + table
            + "'");
  }

  private static List<String> strings(String sql) throws SQLException {
    List<String> values = new ArrayList<>();
    try (Connection connection = connect();
        ResultSet rs = connection.createStatement().executeQuery(sql)) {
      while (rs.next()) {
        values.add(rs.getString(1).toLowerCase(java.util.Locale.ROOT));
      }
    }
    return values;
  }

  private static Connection connect() throws SQLException {
    return DriverManager.getConnection(
        EMPTY_MYSQL.getJdbcUrl(), EMPTY_MYSQL.getUsername(), EMPTY_MYSQL.getPassword());
  }
}
