package ca.musakaleem.remit.intake.batch;

import ca.musakaleem.remit.intake.logging.MdcKeys;
import org.slf4j.MDC;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;

/**
 * Puts the file name, and the batch id when a restarted job already has one, into the MDC so every
 * log line of the job can be correlated; removes them when the job ends.
 */
public class JobMdcListener implements JobExecutionListener {

  @Override
  public void beforeJob(JobExecution jobExecution) {
    MDC.put(MdcKeys.FILE_NAME, jobExecution.getJobParameters().getString(IngestJob.FILE_NAME));
    String batchId = jobExecution.getExecutionContext().getString(IngestJob.BATCH_ID, null);
    if (batchId != null) {
      MDC.put(MdcKeys.CORRELATION_ID, batchId);
    }
  }

  @Override
  public void afterJob(JobExecution jobExecution) {
    MDC.remove(MdcKeys.FILE_NAME);
    MDC.remove(MdcKeys.CORRELATION_ID);
  }
}
