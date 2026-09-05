package ca.musakaleem.remit.intake;

import static org.assertj.core.api.Assertions.assertThat;

import ca.musakaleem.remit.common.IdempotencyKeys;
import ca.musakaleem.remit.common.Topics;
import ca.musakaleem.remit.common.event.BatchEvent;
import ca.musakaleem.remit.common.event.BatchStatus;
import ca.musakaleem.remit.common.event.Channel;
import ca.musakaleem.remit.common.event.EventHeaders;
import ca.musakaleem.remit.common.event.InstructionRejected;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import ca.musakaleem.remit.intake.batch.IngestJob;
import ca.musakaleem.remit.intake.registry.FileRegistryEntry;
import ca.musakaleem.remit.intake.support.AbstractIntakeIT;
import ca.musakaleem.remit.intake.support.TopicReader;
import ca.musakaleem.remit.intake.validation.ReasonCode;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.batch.core.JobExecution;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Runs {@code ingestPaymentFileJob} on each committed sample file (spec 3.7) and checks the
 * published events and the file registry. Order matters: the duplicate file needs the first file to
 * have been registered.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ExtendWith(OutputCaptureExtension.class)
class SampleFilesJobIT extends AbstractIntakeIT {

  private static final Pattern UNMASKED_ACCOUNT = Pattern.compile("(?<![\\w.-])\\d{7,}(?![\\w.-])");

  @Test
  @Order(1)
  void tenValidRowsCompleteAndPublishTenInstructions() throws Exception {
    JobExecution execution = launch("PAY_ACME01_20261015093000.csv", "ACME01");

    assertThat(execution.getStatus())
        .isEqualTo(org.springframework.batch.core.BatchStatus.COMPLETED);
    String batchId = batchId(execution);
    List<ConsumerRecord<String, Object>> instructions =
        TopicReader.read(Topics.INSTRUCTIONS, batchId);
    assertThat(instructions).hasSize(10);
    assertThat(instructions)
        .allSatisfy(
            record -> {
              PaymentInstruction instruction = (PaymentInstruction) record.value();
              assertThat(record.key()).isEqualTo(instruction.debtor().account());
              assertThat(instruction.idempotencyKey())
                  .isEqualTo(
                      IdempotencyKeys.forInstruction("ACME01", instruction.instructionRef()));
              assertThat(instruction.clientId()).isEqualTo("ACME01");
              for (String header :
                  List.of(
                      EventHeaders.EVENT_ID,
                      EventHeaders.EVENT_TYPE,
                      EventHeaders.OCCURRED_AT,
                      EventHeaders.CORRELATION_ID,
                      EventHeaders.SCHEMA_VERSION)) {
                assertThat(EventHeaders.lastValue(record.headers(), header)).isPresent();
              }
            });
    assertThat(instructions)
        .extracting(r -> ((PaymentInstruction) r.value()).channel())
        .contains(Channel.EFT, Channel.WIRE, Channel.INTERAC);
    assertThat(instructions)
        .extracting(r -> ((PaymentInstruction) r.value()).debtor().name())
        .contains("Acme Widgets Ltd");
    assertThat(instructions)
        .extracting(r -> ((PaymentInstruction) r.value()).creditor().name())
        .contains("Maple Leaf Paper, Co");
    assertThat(TopicReader.read(Topics.REJECTED, batchId)).isEmpty();

    List<BatchEvent> batchEvents = batchEvents(batchId);
    assertThat(batchEvents)
        .extracting(BatchEvent::status)
        .containsExactly(BatchStatus.RECEIVED, BatchStatus.COMPLETED);
    BatchEvent completed = batchEvents.get(1);
    assertThat(completed.totalRows()).isEqualTo(10);
    assertThat(completed.acceptedRows()).isEqualTo(10);
    assertThat(completed.rejectedRows()).isZero();
    assertThat(completed.controlTotal()).isEqualByComparingTo("435730.74");
    assertRegistry(batchId, BatchStatus.COMPLETED, null);
  }

  @Test
  @Order(2)
  void fiveInvalidRowsAreRejectedOnePerRuleAndTheRestPublished(CapturedOutput output)
      throws Exception {
    JobExecution execution = launch("PAY_ACME01_20261015100000.csv", "ACME01");

    String batchId = batchId(execution);
    List<ConsumerRecord<String, Object>> instructions =
        TopicReader.read(Topics.INSTRUCTIONS, batchId);
    assertThat(instructions).hasSize(15);
    // ACME-0003 was already in the first ACME01 file: same client, same key, so routing-service
    // will report it as DUPLICATE; intake cannot see other files' rows and publishes it.
    assertThat(instructions)
        .extracting(r -> ((PaymentInstruction) r.value()).idempotencyKey())
        .contains(IdempotencyKeys.forInstruction("ACME01", "ACME-0003"));

    List<InstructionRejected> rejected =
        TopicReader.read(Topics.REJECTED, batchId).stream()
            .map(r -> (InstructionRejected) r.value())
            .toList();
    assertThat(rejected).hasSize(5);
    assertThat(rejected)
        .extracting(InstructionRejected::reasonCodes)
        .containsExactlyInAnyOrder(
            List.of(ReasonCode.INVALID_ACCOUNT.name()),
            List.of(ReasonCode.INVALID_AMOUNT.name()),
            List.of(ReasonCode.INVALID_CURRENCY.name()),
            List.of(ReasonCode.INVALID_VALUE_DATE.name()),
            List.of(ReasonCode.CHANNEL_LIMIT_EXCEEDED.name()));
    assertThat(rejected)
        .extracting(InstructionRejected::lineNumber)
        .containsExactlyInAnyOrder(4L, 7L, 10L, 14L, 17L);
    assertThat(rejected)
        .allSatisfy(
            r -> {
              assertThat(r.clientId()).isEqualTo("ACME01");
              assertThat(r.instructionRef()).startsWith("ACME-01");
              assertThat(UNMASKED_ACCOUNT.matcher(r.rawLine()).find())
                  .as("raw line %s is masked", r.rawLine())
                  .isFalse();
            });

    BatchEvent completed = batchEvents(batchId).get(1);
    assertThat(completed.status()).isEqualTo(BatchStatus.COMPLETED_WITH_ERRORS);
    assertThat(completed.totalRows()).isEqualTo(20);
    assertThat(completed.acceptedRows()).isEqualTo(15);
    assertThat(completed.rejectedRows()).isEqualTo(5);
    assertRegistry(batchId, BatchStatus.COMPLETED_WITH_ERRORS, null);
    // FR-INT-07: rejected rows are logged, but only with masked account numbers.
    assertThat(output.getAll()).contains("Rejected line 4").contains("***0004");
    assertThat(output.getAll()).doesNotContain("2000004").doesNotContain("20000071");
  }

  @Test
  @Order(3)
  void byteIdenticalFileIsRejectedAsDuplicate() throws Exception {
    String fileName = "PAY_ACME01_20261015110000.csv";
    JobExecution execution = launch(fileName, "ACME01");

    assertRejected(execution, ReasonCode.DUPLICATE_FILE);
    assertThat(registry.findAll())
        .extracting(FileRegistryEntry::getFileName)
        .doesNotContain(fileName);
    ConsumerRecord<String, Object> failed = failedBatchEvent(fileName);
    assertThat(EventHeaders.lastValue(failed.headers(), EventHeaders.REASON_CODE))
        .contains("DUPLICATE_FILE");
    String attemptId = ((BatchEvent) failed.value()).batchId().toString();
    assertThat(TopicReader.read(Topics.INSTRUCTIONS, attemptId)).isEmpty();
  }

  @Test
  @Order(4)
  void controlTotalMismatchRejectsTheWholeFile() throws Exception {
    String fileName = "PAY_BETA02_20261015120000.csv";
    JobExecution execution = launch(fileName, "BETA02");

    assertRejected(execution, ReasonCode.CONTROL_TOTAL_MISMATCH);
    String batchId = batchId(execution);
    assertRegistry(batchId, BatchStatus.FAILED, ReasonCode.CONTROL_TOTAL_MISMATCH);
    BatchEvent failed = (BatchEvent) failedBatchEvent(fileName).value();
    assertThat(failed.batchId().toString()).isEqualTo(batchId);
    assertThat(failed.totalRows()).isEqualTo(4);
    assertThat(failed.controlTotal()).isEqualByComparingTo(new BigDecimal("54300.00"));
    assertThat(TopicReader.read(Topics.INSTRUCTIONS, batchId)).isEmpty();
    assertThat(TopicReader.read(Topics.REJECTED, batchId)).isEmpty();
  }

  @Test
  @Order(5)
  void largeWireAndReusedReferenceArePublished() throws Exception {
    JobExecution execution = launch("PAY_BETA02_20261015130000.csv", "BETA02");

    String batchId = batchId(execution);
    List<PaymentInstruction> instructions =
        TopicReader.read(Topics.INSTRUCTIONS, batchId).stream()
            .map(r -> (PaymentInstruction) r.value())
            .toList();
    assertThat(instructions).hasSize(6);
    assertThat(instructions)
        .filteredOn(i -> i.channel() == Channel.WIRE)
        .filteredOn(i -> i.amount().compareTo(new BigDecimal("1000000.00")) >= 0)
        .hasSize(2);
    PaymentInstruction reused =
        instructions.stream()
            .filter(i -> i.instructionRef().equals("ACME-0001"))
            .findFirst()
            .orElseThrow();
    // Keys are scoped to the client (spec 3.6), so BETA02 reusing ACME01's reference is new.
    assertThat(reused.idempotencyKey())
        .isEqualTo(IdempotencyKeys.forInstruction("BETA02", "ACME-0001"))
        .isNotEqualTo(IdempotencyKeys.forInstruction("ACME01", "ACME-0001"));
    assertThat(batchEvents(batchId).get(1).status()).isEqualTo(BatchStatus.COMPLETED);
    assertRegistry(batchId, BatchStatus.COMPLETED, null);
  }

  private JobExecution launch(String sample, String clientId) throws Exception {
    Path staged = stageSample(sample);
    return jobLauncherTestUtils.launchJob(paramsFor(staged, clientId));
  }

  private static String batchId(JobExecution execution) {
    return execution.getExecutionContext().getString(IngestJob.BATCH_ID);
  }

  private static List<BatchEvent> batchEvents(String batchId) {
    return TopicReader.read(Topics.BATCH_EVENTS, batchId).stream()
        .map(r -> (BatchEvent) r.value())
        .toList();
  }

  private static ConsumerRecord<String, Object> failedBatchEvent(String fileName) {
    return TopicReader.readAll(Topics.BATCH_EVENTS).stream()
        .filter(r -> ((BatchEvent) r.value()).fileName().equals(fileName))
        .filter(r -> ((BatchEvent) r.value()).status() == BatchStatus.FAILED)
        .findFirst()
        .orElseThrow();
  }

  private static void assertRejected(JobExecution execution, ReasonCode reason) {
    assertThat(execution.getStatus())
        .isEqualTo(org.springframework.batch.core.BatchStatus.COMPLETED);
    assertThat(execution.getExitStatus().getExitCode()).isEqualTo(IngestJob.REJECTED);
    assertThat(execution.getExecutionContext().getString(IngestJob.REJECT_REASON))
        .isEqualTo(reason.name());
    assertThat(execution.getStepExecutions())
        .extracting(s -> s.getStepName())
        .containsExactly(IngestJob.FILE_VALIDATION_STEP);
  }

  private void assertRegistry(String batchId, BatchStatus status, ReasonCode reason) {
    FileRegistryEntry entry = registry.findByBatchId(batchId).orElseThrow();
    assertThat(entry.getStatus()).isEqualTo(status);
    assertThat(entry.getReasonCode()).isEqualTo(reason);
    assertThat(entry.getReceivedAt()).isNotNull();
    assertThat(entry.getCompletedAt()).isNotNull();
  }
}
