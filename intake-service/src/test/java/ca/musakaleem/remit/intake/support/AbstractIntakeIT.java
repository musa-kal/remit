package ca.musakaleem.remit.intake.support;

import ca.musakaleem.remit.intake.batch.FileHashing;
import ca.musakaleem.remit.intake.batch.IngestJob;
import ca.musakaleem.remit.intake.registry.FileRegistryRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for intake integration tests: the full application on a random port against the shared
 * Testcontainers MySQL, Kafka and SFTP, with the business date pinned to 2026-10-15 by the test
 * profile.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractIntakeIT {

  /** Repository root, so tests can read the committed sample files. */
  protected static final Path REPO_ROOT = Path.of(System.getProperty("user.dir")).getParent();

  private static final Path STAGING_DIR = tempStagingDir();

  protected JobLauncherTestUtils jobLauncherTestUtils;

  @Autowired protected FileRegistryRepository registry;

  @Autowired
  void jobLauncherTestUtils(JobLauncher jobLauncher, JobRepository jobRepository, Job job) {
    // Built by hand instead of @SpringBatchTest, whose listeners try to call any test method
    // returning a JobExecution.
    jobLauncherTestUtils = new JobLauncherTestUtils();
    jobLauncherTestUtils.setJobLauncher(jobLauncher);
    jobLauncherTestUtils.setJobRepository(jobRepository);
    jobLauncherTestUtils.setJob(job);
  }

  @DynamicPropertySource
  static void containers(DynamicPropertyRegistry registry) {
    IntakeContainers.register(registry);
    registry.add("remit.intake.staging-dir", STAGING_DIR::toString);
  }

  private static Path tempStagingDir() {
    try {
      return Files.createTempDirectory("remit-staging-it");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Copies content into a fresh staging directory, as the poller would.
   *
   * @param fileName file name
   * @param content file content
   * @return staged path
   */
  protected static Path stage(String fileName, byte[] content) {
    try {
      Path dir = Files.createTempDirectory("staged");
      return Files.write(dir.resolve(fileName), content);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Stages text content.
   *
   * @param fileName file name
   * @param content file content
   * @return staged path
   */
  protected static Path stage(String fileName, String content) {
    return stage(fileName, content.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Stages one of the committed sample files.
   *
   * @param fileName sample file name
   * @return staged path
   */
  protected static Path stageSample(String fileName) {
    try {
      return stage(fileName, Files.readAllBytes(REPO_ROOT.resolve("samples").resolve(fileName)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * The job parameters the poller would use for a staged file.
   *
   * @param staged staged path
   * @param clientId client id
   * @return job parameters
   */
  protected static JobParameters paramsFor(Path staged, String clientId) {
    return new JobParametersBuilder()
        .addString(IngestJob.FILE_NAME, staged.getFileName().toString())
        .addString(IngestJob.CLIENT_ID, clientId)
        .addString(IngestJob.FILE_HASH, FileHashing.sha256(staged))
        .addString(IngestJob.STAGED_PATH, staged.toString())
        .toJobParameters();
  }
}
