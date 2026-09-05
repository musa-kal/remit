package ca.musakaleem.remit.intake;

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
          .withDatabaseName("remit_intake")
          .withUsername("remit_intake")
          .withPassword("test-only");

  @Test
  @Order(1)
  void migratesAnEmptySchemaOnceAndValidates() throws SQLException {
    Flyway flyway =
        Flyway.configure()
            .dataSource(
                EMPTY_MYSQL.getJdbcUrl(), EMPTY_MYSQL.getUsername(), EMPTY_MYSQL.getPassword())
            .locations("classpath:db/migration")
            .load();

    MigrateResult first = flyway.migrate();
    MigrateResult second = flyway.migrate();
    flyway.validate();

    assertThat(first.success).isTrue();
    assertThat(first.migrationsExecuted).isEqualTo(2);
    assertThat(second.migrationsExecuted).isZero();
    assertThat(tables())
        .contains(
            "BATCH_JOB_INSTANCE",
            "BATCH_JOB_EXECUTION",
            "BATCH_JOB_EXECUTION_PARAMS",
            "BATCH_JOB_EXECUTION_CONTEXT",
            "BATCH_STEP_EXECUTION",
            "BATCH_STEP_EXECUTION_CONTEXT",
            "BATCH_JOB_SEQ",
            "BATCH_JOB_EXECUTION_SEQ",
            "BATCH_STEP_EXECUTION_SEQ",
            "file_registry");
  }

  @Test
  @Order(2)
  void fileHashAndBatchIdAreUnique() throws SQLException {
    Flyway.configure()
        .dataSource(EMPTY_MYSQL.getJdbcUrl(), EMPTY_MYSQL.getUsername(), EMPTY_MYSQL.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();

    try (Connection connection = connect();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(insert("11111111-1111-1111-1111-111111111111", "a".repeat(64)));

      assertThatThrownBy(
              () ->
                  statement.executeUpdate(
                      insert("22222222-2222-2222-2222-222222222222", "a".repeat(64))))
          .isInstanceOf(SQLIntegrityConstraintViolationException.class)
          .hasMessageContaining("uk_file_registry_file_hash");
      assertThatThrownBy(
              () ->
                  statement.executeUpdate(
                      insert("11111111-1111-1111-1111-111111111111", "b".repeat(64))))
          .isInstanceOf(SQLIntegrityConstraintViolationException.class)
          .hasMessageContaining("uk_file_registry_batch_id");
    }
  }

  private static String insert(String batchId, String hash) {
    return "INSERT INTO file_registry (batch_id, client_id, file_name, file_hash, status,"
        + " received_at) VALUES ('"
        + batchId
        + "', 'ACME01', 'PAY_ACME01_20261015093000.csv', '"
        + hash
        + "', 'RECEIVED', NOW(3))";
  }

  private static List<String> tables() throws SQLException {
    List<String> tables = new ArrayList<>();
    try (Connection connection = connect();
        ResultSet rs =
            connection
                .createStatement()
                .executeQuery(
                    "SELECT table_name FROM information_schema.tables"
                        + " WHERE table_schema = 'remit_intake'")) {
      while (rs.next()) {
        tables.add(rs.getString(1));
      }
    }
    return tables;
  }

  private static Connection connect() throws SQLException {
    return DriverManager.getConnection(
        EMPTY_MYSQL.getJdbcUrl(), EMPTY_MYSQL.getUsername(), EMPTY_MYSQL.getPassword());
  }
}
