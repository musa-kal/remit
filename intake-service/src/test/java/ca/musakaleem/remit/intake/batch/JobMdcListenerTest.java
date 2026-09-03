package ca.musakaleem.remit.intake.batch;

import static org.assertj.core.api.Assertions.assertThat;

import ca.musakaleem.remit.intake.logging.MdcKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.test.MetaDataInstanceFactory;

class JobMdcListenerTest {

  private final JobMdcListener listener = new JobMdcListener();

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void putsFileNameAndKnownBatchIdThenRemovesThem() {
    JobExecution execution =
        MetaDataInstanceFactory.createJobExecution(
            IngestJob.JOB_NAME,
            1L,
            2L,
            new JobParametersBuilder().addString(IngestJob.FILE_NAME, "f.csv").toJobParameters());
    execution.getExecutionContext().putString(IngestJob.BATCH_ID, "b-1");

    listener.beforeJob(execution);

    assertThat(MDC.get(MdcKeys.FILE_NAME)).isEqualTo("f.csv");
    assertThat(MDC.get(MdcKeys.CORRELATION_ID)).isEqualTo("b-1");

    listener.afterJob(execution);

    assertThat(MDC.get(MdcKeys.FILE_NAME)).isNull();
    assertThat(MDC.get(MdcKeys.CORRELATION_ID)).isNull();
  }

  @Test
  void leavesCorrelationIdUnsetOnAFirstRun() {
    JobExecution execution =
        MetaDataInstanceFactory.createJobExecution(
            IngestJob.JOB_NAME,
            1L,
            2L,
            new JobParametersBuilder().addString(IngestJob.FILE_NAME, "f.csv").toJobParameters());

    listener.beforeJob(execution);

    assertThat(MDC.get(MdcKeys.CORRELATION_ID)).isNull();
  }
}
