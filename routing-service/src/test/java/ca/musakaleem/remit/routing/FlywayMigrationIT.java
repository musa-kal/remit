package ca.musakaleem.remit.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
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
          .withDatabaseName("remit_routing")
          .withUsername("remit_routing")
          .withPassword("test-only");

  @Test
  @Order(1)
  void migratesAnEmptySchemaOnceAndValidates() throws SQLException {
    Flyway flyway = flyway();

    MigrateResult first = flyway.migrate();
    MigrateResult second = flyway.migrate();
    flyway.validate();

    assertThat(first.success).isTrue();
    assertThat(first.migrationsExecuted).isEqualTo(1);
    assertThat(second.migrationsExecuted).isZero();
    assertThat(columns())
        .containsExactly(
            Map.entry("idempotency_key", "char(64)"),
            Map.entry("batch_id", "char(36)"),
            Map.entry("channel", "varchar(10)"),
            Map.entry("amount", "decimal(15,2)"),
            Map.entry("status", "varchar(20)"),
            Map.entry("attempts", "int"),
            Map.entry("created_at", "timestamp(3)"),
            Map.entry("updated_at", "timestamp(3)"),
            Map.entry("version", "bigint"));
  }

  @Test
  @Order(2)
  void idempotencyKeyIsTheUniquePrimaryKeyAndBatchIdIsIndexed() throws SQLException {
    flyway().migrate();

    try (Connection connection = connect();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(insert("a".repeat(64)));

      assertThatThrownBy(() -> statement.executeUpdate(insert("a".repeat(64))))
          .isInstanceOf(SQLIntegrityConstraintViolationException.class)
          .hasMessageContaining("PRIMARY");
      try (ResultSet indexes =
          statement.executeQuery(
              "SHOW INDEX FROM processed_instruction WHERE Column_name = 'batch_id'")) {
        assertThat(indexes.next()).isTrue();
        assertThat(indexes.getString("Key_name")).isEqualTo("ix_processed_instruction_batch_id");
      }
    }
  }

  private static Flyway flyway() {
    return Flyway.configure()
        .dataSource(EMPTY_MYSQL.getJdbcUrl(), EMPTY_MYSQL.getUsername(), EMPTY_MYSQL.getPassword())
        .locations("classpath:db/migration")
        .load();
  }

  private static String insert(String key) {
    return "INSERT INTO processed_instruction (idempotency_key, batch_id, channel, amount, status,"
        + " attempts, created_at, updated_at, version) VALUES ('"
        + key
        + "', '11111111-1111-1111-1111-111111111111', 'EFT', 1250.00, 'ROUTING', 1, NOW(3),"
        + " NOW(3), 0)";
  }

  private static Map<String, String> columns() throws SQLException {
    Map<String, String> columns = new LinkedHashMap<>();
    try (Connection connection = connect();
        ResultSet rs =
            connection
                .createStatement()
                .executeQuery(
                    "SELECT column_name, column_type FROM information_schema.columns"
                        + " WHERE table_schema = 'remit_routing'"
                        + " AND table_name = 'processed_instruction' ORDER BY ordinal_position")) {
      while (rs.next()) {
        columns.put(rs.getString(1), rs.getString(2));
      }
    }
    return columns;
  }

  private static Connection connect() throws SQLException {
    return DriverManager.getConnection(
        EMPTY_MYSQL.getJdbcUrl(), EMPTY_MYSQL.getUsername(), EMPTY_MYSQL.getPassword());
  }
}
