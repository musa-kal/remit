package ca.musakaleem.remit.intake.support;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

/**
 * MySQL 8.4, Kafka 3.9 (KRaft) and atmoz/sftp, started once per test JVM and shared by every
 * integration test (the singleton container pattern). Ryuk removes them when the JVM exits.
 */
public final class IntakeContainers {

  /** SFTP login used by the test container; not a real credential. */
  public static final String SFTP_USER = "remit";

  /** SFTP password used by the test container; not a real credential. */
  public static final String SFTP_PASSWORD = "test-only";

  /** Home directory of the SFTP user inside the container. */
  public static final String SFTP_HOME = "/home/" + SFTP_USER;

  public static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
          .withDatabaseName("remit_intake")
          .withUsername("remit_intake")
          .withPassword("test-only");

  public static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.2"));

  public static final GenericContainer<?> SFTP =
      new GenericContainer<>(DockerImageName.parse("atmoz/sftp:alpine"))
          .withCommand(
              SFTP_USER + ":" + SFTP_PASSWORD + ":1001:1001:inbound,processing,processed,error")
          .withExposedPorts(22)
          .waitingFor(Wait.forLogMessage(".*Server listening on.*", 1));

  static {
    Startables.deepStart(MYSQL, KAFKA, SFTP).join();
  }

  private IntakeContainers() {}

  /**
   * Points the application at the containers.
   *
   * @param registry dynamic property registry of the test context
   */
  public static void register(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    registry.add("remit.sftp.host", SFTP::getHost);
    registry.add("remit.sftp.port", () -> SFTP.getMappedPort(22));
    registry.add("remit.sftp.user", () -> SFTP_USER);
    registry.add("remit.sftp.password", () -> SFTP_PASSWORD);
  }

  /**
   * Lists a directory in the SFTP user's home.
   *
   * @param directory directory name, for example {@code processed}
   * @return file names
   */
  public static List<String> sftpFiles(String directory) {
    Container.ExecResult result = exec("ls", "-1", SFTP_HOME + "/" + directory);
    return Arrays.stream(result.getStdout().split("\n")).filter(s -> !s.isBlank()).toList();
  }

  /**
   * Reads a text file in the SFTP user's home.
   *
   * @param path path relative to the home directory
   * @return content
   */
  public static String sftpRead(String path) {
    return exec("cat", SFTP_HOME + "/" + path).getStdout();
  }

  private static Container.ExecResult exec(String... command) {
    try {
      return SFTP.execInContainer(command);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
