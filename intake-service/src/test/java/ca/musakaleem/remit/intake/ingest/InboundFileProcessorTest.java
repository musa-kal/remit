package ca.musakaleem.remit.intake.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ca.musakaleem.remit.intake.config.IntakeProperties;
import ca.musakaleem.remit.intake.config.SftpProperties;
import ca.musakaleem.remit.intake.sftp.SftpFileGateway;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class InboundFileProcessorTest {

  private static final String FILE = "PAY_ACME01_20261015093000.csv";

  @TempDir Path dir;

  private SftpFileGateway gateway;
  private IngestJobService jobs;
  private StagingArea staging;
  private InboundFileProcessor processor;
  private SftpProperties sftp;

  @BeforeEach
  void setUp() {
    gateway = mock(SftpFileGateway.class);
    jobs = mock(IngestJobService.class);
    staging = new StagingArea(new IntakeProperties(dir, 100, 1000, Duration.ofSeconds(1)));
    sftp =
        new SftpProperties(
            "localhost",
            22,
            "remit",
            "secret",
            Duration.ofSeconds(30),
            true,
            true,
            "/inbound",
            "/processing",
            "/processed",
            "/error");
    processor = new InboundFileProcessor(gateway, staging, jobs, sftp);
    doAnswer(
            invocation -> {
              Files.writeString(invocation.getArgument(2, Path.class), "content");
              return null;
            })
        .when(gateway)
        .download(eq("/inbound"), anyString(), any(Path.class));
  }

  @Test
  void invalidNameGoesStraightToErrorWithANote() {
    processor.process("payments.csv");

    verify(gateway).move("/inbound", "/error", "payments.csv");
    ArgumentCaptor<String> note = ArgumentCaptor.forClass(String.class);
    verify(gateway).writeText(eq("/error"), eq("payments.csv.error.txt"), note.capture());
    assertThat(note.getValue()).startsWith("INVALID_FILENAME\n");
    verify(gateway, never()).download(anyString(), anyString(), any());
    verifyNoInteractions(jobs);
  }

  @Test
  void processedFileEndsInProcessedAndStagingIsCleaned() {
    when(jobs.launch(any(), eq("ACME01")))
        .thenAnswer(
            invocation -> {
              StagedFile staged = invocation.getArgument(0);
              assertThat(staged.path()).exists();
              return new JobOutcome(
                  1L, FILE, staged.path(), JobOutcome.Disposition.PROCESSED, null, null);
            });

    processor.process(FILE);

    InOrder order = inOrder(gateway, jobs);
    order.verify(gateway).download(eq("/inbound"), eq(FILE), any());
    order.verify(gateway).move("/inbound", "/processing", FILE);
    order.verify(jobs).launch(any(), eq("ACME01"));
    order.verify(gateway).move("/processing", "/processed", FILE);
    assertThat(dir).isEmptyDirectory();
  }

  @Test
  void rejectedFileEndsInErrorWithItsReason() {
    when(jobs.launch(any(), eq("ACME01")))
        .thenAnswer(
            invocation ->
                new JobOutcome(
                    2L,
                    FILE,
                    invocation.getArgument(0, StagedFile.class).path(),
                    JobOutcome.Disposition.REJECTED,
                    "CONTROL_TOTAL_MISMATCH",
                    "trailer control_total 1.00 but rows sum to 2.00"));

    processor.process(FILE);

    verify(gateway).move("/processing", "/error", FILE);
    verify(gateway)
        .writeText(
            "/error",
            FILE + ".error.txt",
            "CONTROL_TOTAL_MISMATCH\ntrailer control_total 1.00 but rows sum to 2.00\n");
    assertThat(dir).isEmptyDirectory();
  }

  @Test
  void incompleteJobLeavesTheFileInProcessingAndKeepsTheStagedCopy() {
    when(jobs.launch(any(), eq("ACME01")))
        .thenAnswer(
            invocation ->
                new JobOutcome(
                    3L,
                    FILE,
                    invocation.getArgument(0, StagedFile.class).path(),
                    JobOutcome.Disposition.INCOMPLETE,
                    null,
                    "job FAILED"));

    processor.process(FILE);

    verify(gateway).move("/inbound", "/processing", FILE);
    verify(gateway, never()).move(eq("/processing"), anyString(), anyString());
    assertThat(dir).isNotEmptyDirectory();
  }

  @Test
  void pollerProcessesEveryFileAndSurvivesFailures() {
    when(gateway.listCsvFiles("/inbound")).thenReturn(List.of("bad.csv", "worse.csv"));
    doThrow(new IllegalStateException("sftp down"))
        .when(gateway)
        .move("/inbound", "/error", "bad.csv");
    SftpPoller poller = new SftpPoller(gateway, processor, sftp);

    poller.poll();

    verify(gateway).move("/inbound", "/error", "worse.csv");
  }

  @Test
  void pollerSkipsARoundWhenListingFails() {
    when(gateway.listCsvFiles("/inbound")).thenThrow(new IllegalStateException("auth failed"));
    SftpPoller poller = new SftpPoller(gateway, processor, sftp);

    poller.poll();

    verify(gateway, never()).move(anyString(), anyString(), anyString());
  }

  @Test
  void pollerDoesNothingForAnEmptyInbox() {
    when(gateway.listCsvFiles("/inbound")).thenReturn(List.of());

    new SftpPoller(gateway, processor, sftp).poll();

    verifyNoInteractions(jobs);
  }

  @Test
  void sftpPropertiesToStringHidesThePassword() {
    assertThat(sftp.toString()).doesNotContain("secret").contains("password=****");
  }
}
