package ca.musakaleem.remit.intake;

import static ca.musakaleem.remit.intake.support.IntakeContainers.SFTP;
import static ca.musakaleem.remit.intake.support.IntakeContainers.SFTP_HOME;
import static ca.musakaleem.remit.intake.support.IntakeContainers.sftpFiles;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import ca.musakaleem.remit.common.Topics;
import ca.musakaleem.remit.common.event.BatchEvent;
import ca.musakaleem.remit.common.event.BatchStatus;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import ca.musakaleem.remit.intake.batch.IngestJob;
import ca.musakaleem.remit.intake.publish.EventPublishException;
import ca.musakaleem.remit.intake.publish.IntakeEventPublisher;
import ca.musakaleem.remit.intake.support.AbstractIntakeIT;
import ca.musakaleem.remit.intake.support.PaymentFiles;
import ca.musakaleem.remit.intake.support.TopicReader;
import ca.musakaleem.remit.intake.validation.ReasonCode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.images.builder.Transferable;

/**
 * FR-INT-06: a job that fails part way through the chunk step resumes after the last committed
 * chunk when restarted through the runbook's actuator endpoint. Also covers the skip limit.
 */
@TestPropertySource(properties = "remit.intake.skip-limit=3")
class JobRestartIT extends AbstractIntakeIT {

  @MockitoSpyBean IntakeEventPublisher publisher;

  @Autowired JobExplorer jobExplorer;

  @Autowired TestRestTemplate http;

  @Test
  void restartResumesAfterTheLastCommittedChunk() {
    String name = "PAY_RSTRT1_20261015160000.csv";
    String content = PaymentFiles.file(PaymentFiles.validRows("RST", 250));
    Path staged = stage(name, content);
    failOnceAtPublish(150);

    JobExecution first = launch(staged, "RSTRT1");

    assertThat(first.getStatus()).isEqualTo(org.springframework.batch.core.BatchStatus.FAILED);
    StepExecution failedStep = step(first, IngestJob.INSTRUCTION_STEP);
    assertThat(failedStep.getWriteCount()).isEqualTo(100);
    assertThat(failedStep.getCommitCount()).isEqualTo(1);
    String batchId = first.getExecutionContext().getString(IngestJob.BATCH_ID);
    assertThat(registry.findByBatchId(batchId).orElseThrow().getStatus())
        .isEqualTo(BatchStatus.RECEIVED);

    // The poller left the remote file in /processing; the runbook restarts the execution.
    SFTP.copyFileToContainer(Transferable.of(content), SFTP_HOME + "/processing/" + name);
    ResponseEntity<String> response =
        http.postForEntity("/actuator/ingestjobs/" + first.getId(), null, String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("\"disposition\":\"PROCESSED\"");
    JobExecution second = jobExplorer.getLastJobExecution(first.getJobInstance());
    assertThat(second.getId()).isNotEqualTo(first.getId());
    assertThat(second.getStatus()).isEqualTo(org.springframework.batch.core.BatchStatus.COMPLETED);
    assertThat(second.getStepExecutions())
        .extracting(StepExecution::getStepName)
        .containsExactly(IngestJob.INSTRUCTION_STEP, IngestJob.FINALIZE_STEP);
    StepExecution resumed = step(second, IngestJob.INSTRUCTION_STEP);
    assertThat(resumed.getReadCount()).isEqualTo(150);
    assertThat(resumed.getWriteCount()).isEqualTo(150);

    List<PaymentInstruction> published =
        TopicReader.read(Topics.INSTRUCTIONS, batchId).stream()
            .map(r -> (PaymentInstruction) r.value())
            .toList();
    // Rows 101 to 149 were sent before the failure and again after the restart (at least once).
    assertThat(published.stream().map(PaymentInstruction::idempotencyKey).distinct()).hasSize(250);
    assertThat(published).hasSizeGreaterThanOrEqualTo(250);
    BatchEvent completed = lastBatchEvent(batchId);
    assertThat(completed.status()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(completed.acceptedRows()).isEqualTo(250);
    assertThat(completed.rejectedRows()).isZero();
    assertThat(registry.findByBatchId(batchId).orElseThrow().getStatus())
        .isEqualTo(BatchStatus.COMPLETED);
    assertThat(sftpFiles("processed")).contains(name);
    assertThat(staged).doesNotExist();
  }

  @Test
  void skipLimitStopsTheStepAndFailsTheBatch() {
    // Invalid rows 10 and 50 fall in the first chunk, 120 and 150 in the second. The skip limit
    // is 3, so row 150 stops the step part way through the second chunk.
    List<String> rows = new ArrayList<>(PaymentFiles.validRows("SKIP", 200));
    for (int index : new int[] {10, 50, 120, 150}) {
      rows.set(index - 1, PaymentFiles.row("SKIP-BAD-" + index, "0.00"));
    }
    Path staged = stage("PAY_SKIP01_20261015170000.csv", PaymentFiles.file(rows));

    JobExecution execution = launch(staged, "SKIP01");

    assertThat(execution.getStatus())
        .isEqualTo(org.springframework.batch.core.BatchStatus.COMPLETED);
    assertThat(step(execution, IngestJob.INSTRUCTION_STEP).getExitStatus().getExitCode())
        .isEqualTo(IngestJob.SKIP_LIMIT_EXCEEDED);
    assertThat(execution.getExecutionContext().getString(IngestJob.REJECT_REASON))
        .isEqualTo(ReasonCode.SKIP_LIMIT_EXCEEDED.name());
    String batchId = execution.getExecutionContext().getString(IngestJob.BATCH_ID);
    assertThat(registry.findByBatchId(batchId).orElseThrow().getReasonCode())
        .isEqualTo(ReasonCode.SKIP_LIMIT_EXCEEDED);
    BatchEvent failed = lastBatchEvent(batchId);
    assertThat(failed.status()).isEqualTo(BatchStatus.FAILED);
    assertThat(failed.acceptedRows()).isEqualTo(98);
    // Rows 10, 50 and 120 were skipped and published; row 150 hit the limit. The valid rows of
    // the second chunk were rolled back and never published.
    assertThat(failed.rejectedRows()).isEqualTo(3);
    assertThat(TopicReader.read(Topics.REJECTED, batchId)).hasSize(3);
    assertThat(TopicReader.read(Topics.INSTRUCTIONS, batchId)).hasSize(98);
  }

  private void failOnceAtPublish(int failingCall) {
    AtomicInteger calls = new AtomicInteger();
    doAnswer(
            invocation -> {
              if (calls.incrementAndGet() == failingCall) {
                throw new EventPublishException("simulated broker outage", null);
              }
              return invocation.callRealMethod();
            })
        .when(publisher)
        .publishInstruction(any());
  }

  private JobExecution launch(Path staged, String clientId) {
    try {
      return jobLauncherTestUtils.launchJob(paramsFor(staged, clientId));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static StepExecution step(JobExecution execution, String name) {
    return execution.getStepExecutions().stream()
        .filter(s -> s.getStepName().equals(name))
        .findFirst()
        .orElseThrow();
  }

  private static BatchEvent lastBatchEvent(String batchId) {
    List<BatchEvent> events =
        TopicReader.read(Topics.BATCH_EVENTS, batchId).stream()
            .map(r -> (BatchEvent) r.value())
            .toList();
    return events.get(events.size() - 1);
  }
}
