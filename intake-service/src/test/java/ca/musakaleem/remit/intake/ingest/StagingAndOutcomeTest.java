package ca.musakaleem.remit.intake.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.musakaleem.remit.intake.batch.FileHashing;
import ca.musakaleem.remit.intake.batch.IngestJob;
import ca.musakaleem.remit.intake.config.IntakeProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.test.MetaDataInstanceFactory;

class StagingAndOutcomeTest {

  @TempDir Path dir;

  @Test
  void stagesUnderTheContentHashAndDiscardsTheDirectory() throws IOException {
    StagingArea staging = staging();

    StagedFile staged = staging.stage("f.csv", target -> write(target, "hello"));

    assertThat(staged.fileName()).isEqualTo("f.csv");
    assertThat(staged.fileHash()).isEqualTo(FileHashing.sha256(staged.path()));
    assertThat(staged.path()).isEqualTo(staging.root().resolve(staged.fileHash()).resolve("f.csv"));
    assertThat(Files.readString(staged.path())).isEqualTo("hello");
    assertThat(staging.root().resolve("f.csv.part")).doesNotExist();

    staging.discard(staged.path());

    assertThat(staged.path()).doesNotExist();
    assertThat(staged.path().getParent()).doesNotExist();
  }

  @Test
  void sameNameWithDifferentContentGetsItsOwnDirectory() {
    StagingArea staging = staging();

    StagedFile first = staging.stage("f.csv", target -> write(target, "one"));
    StagedFile second = staging.stage("f.csv", target -> write(target, "two"));

    assertThat(first.path()).isNotEqualTo(second.path()).exists();
    assertThat(second.path()).exists();
  }

  @Test
  void discardKeepsADirectoryThatStillHoldsAnotherFile() throws IOException {
    StagingArea staging = staging();
    StagedFile first = staging.stage("a.csv", target -> write(target, "same"));
    StagedFile second = staging.stage("b.csv", target -> write(target, "same"));

    staging.discard(first.path());

    assertThat(first.path()).doesNotExist();
    assertThat(second.path()).exists();
    staging.discard(dir.resolve("never-staged.csv"));
  }

  @Test
  void stageWrapsIoFailures() throws IOException {
    Path blocked = dir.resolve("blocked");
    Files.writeString(blocked, "a file where the root directory should be");
    StagingArea staging =
        new StagingArea(new IntakeProperties(blocked, 100, 1000, Duration.ofSeconds(1)));

    assertThatThrownBy(() -> staging.stage("f.csv", target -> write(target, "x")))
        .isInstanceOf(UncheckedIOException.class);
  }

  @Test
  void completedExecutionIsProcessed() {
    JobExecution execution = execution(BatchStatus.COMPLETED);

    JobOutcome outcome = JobOutcome.from(execution);

    assertThat(outcome.disposition()).isEqualTo(JobOutcome.Disposition.PROCESSED);
    assertThat(outcome.fileName()).isEqualTo("PAY_ACME01_20261015093000.csv");
    assertThat(outcome.stagedPath()).isEqualTo(Path.of("/staging/h/PAY_ACME01_20261015093000.csv"));
    assertThat(outcome.executionId()).isEqualTo(execution.getId());
  }

  @Test
  void completedExecutionWithARejectReasonIsRejected() {
    JobExecution execution = execution(BatchStatus.COMPLETED);
    execution.getExecutionContext().putString(IngestJob.REJECT_REASON, "EMPTY_FILE");
    execution.getExecutionContext().putString(IngestJob.REJECT_DETAIL, "no rows");

    JobOutcome outcome = JobOutcome.from(execution);

    assertThat(outcome.disposition()).isEqualTo(JobOutcome.Disposition.REJECTED);
    assertThat(outcome.errorNote()).isEqualTo("EMPTY_FILE\nno rows\n");
  }

  @Test
  void failedExecutionIsIncomplete() {
    JobExecution execution = execution(BatchStatus.FAILED);
    execution.setExitStatus(ExitStatus.FAILED.addExitDescription("broker down"));

    JobOutcome outcome = JobOutcome.from(execution);

    assertThat(outcome.disposition()).isEqualTo(JobOutcome.Disposition.INCOMPLETE);
    assertThat(outcome.detail()).contains("FAILED").contains("broker down");
  }

  @Test
  void factoryOutcomes() {
    StagedFile staged = new StagedFile("f.csv", "h", Path.of("/s/h/f.csv"));

    assertThat(JobOutcome.alreadyProcessed(staged).reasonCode()).isEqualTo("DUPLICATE_FILE");
    assertThat(JobOutcome.notRun(staged, "why").disposition())
        .isEqualTo(JobOutcome.Disposition.INCOMPLETE);
    assertThat(
            new JobOutcome(null, "f", null, JobOutcome.Disposition.REJECTED, "X", null).errorNote())
        .isEqualTo("X\n");
  }

  private StagingArea staging() {
    return new StagingArea(
        new IntakeProperties(dir.resolve("staging"), 100, 1000, Duration.ofSeconds(1)));
  }

  private static void write(Path target, String content) {
    try {
      Files.writeString(target, content);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static JobExecution execution(BatchStatus status) {
    JobExecution execution =
        MetaDataInstanceFactory.createJobExecution(
            IngestJob.JOB_NAME,
            1L,
            7L,
            new JobParametersBuilder()
                .addString(IngestJob.FILE_NAME, "PAY_ACME01_20261015093000.csv")
                .addString(IngestJob.STAGED_PATH, "/staging/h/PAY_ACME01_20261015093000.csv")
                .toJobParameters());
    execution.setStatus(status);
    return execution;
  }
}
