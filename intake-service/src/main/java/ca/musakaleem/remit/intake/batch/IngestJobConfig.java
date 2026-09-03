package ca.musakaleem.remit.intake.batch;

import ca.musakaleem.remit.common.event.PaymentInstruction;
import ca.musakaleem.remit.intake.config.IntakeProperties;
import ca.musakaleem.remit.intake.publish.IntakeEventPublisher;
import ca.musakaleem.remit.intake.registry.FileRegistryRepository;
import ca.musakaleem.remit.intake.validation.InstructionValidationException;
import ca.musakaleem.remit.intake.validation.InstructionValidator;
import ca.musakaleem.remit.intake.validation.RawInstruction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.SkipListener;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.job.DefaultJobParametersValidator;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Wires {@code ingestPaymentFileJob} (FR-INT-03, FR-INT-04):
 *
 * <pre>
 * fileValidationStep --REJECTED--> end (job COMPLETED, exit REJECTED)
 *        |COMPLETED
 * instructionStep --COMPLETED or SKIP_LIMIT_EXCEEDED--> finalizeStep --> end
 *        |any other failure: job FAILED, restartable from the last committed chunk
 * </pre>
 */
@Configuration(proxyBeanMethods = false)
public class IngestJobConfig {

  /**
   * The ingest job. Its four identifying parameters make one job instance per staged file.
   *
   * @param jobRepository Spring Batch repository in {@code remit_intake}
   * @param fileValidationStep file-level checks
   * @param instructionStep row validation and publishing
   * @param finalizeStep final status
   * @return the job
   */
  @Bean
  public Job ingestPaymentFileJob(
      JobRepository jobRepository,
      Step fileValidationStep,
      Step instructionStep,
      Step finalizeStep) {
    return new JobBuilder(IngestJob.JOB_NAME, jobRepository)
        .validator(
            new DefaultJobParametersValidator(
                new String[] {
                  IngestJob.FILE_NAME,
                  IngestJob.CLIENT_ID,
                  IngestJob.FILE_HASH,
                  IngestJob.STAGED_PATH
                },
                new String[0]))
        .listener(new JobMdcListener())
        .start(fileValidationStep)
        .on(IngestJob.REJECTED)
        .end(IngestJob.REJECTED)
        .from(fileValidationStep)
        .on(ExitStatus.COMPLETED.getExitCode())
        .to(instructionStep)
        .from(fileValidationStep)
        .on("*")
        .fail()
        .from(instructionStep)
        .on(ExitStatus.COMPLETED.getExitCode())
        .to(finalizeStep)
        .from(instructionStep)
        .on(IngestJob.SKIP_LIMIT_EXCEEDED)
        .to(finalizeStep)
        .from(instructionStep)
        .on("*")
        .fail()
        .end()
        .build();
  }

  /**
   * File-level validation step.
   *
   * @param jobRepository job repository
   * @param transactionManager JPA transaction manager shared with the registry
   * @param registry file registry
   * @param publisher event publisher
   * @param clock clock
   * @return the step
   */
  @Bean
  public Step fileValidationStep(
      JobRepository jobRepository,
      PlatformTransactionManager transactionManager,
      FileRegistryRepository registry,
      IntakeEventPublisher publisher,
      Clock clock) {
    return new StepBuilder(IngestJob.FILE_VALIDATION_STEP, jobRepository)
        .tasklet(new FileValidationTasklet(registry, publisher, clock), transactionManager)
        .build();
  }

  /**
   * Chunk step: read rows, validate, publish; invalid rows are skipped and published as rejected.
   *
   * @param jobRepository job repository
   * @param transactionManager transaction manager
   * @param instructionReader step scoped reader
   * @param instructionProcessor step scoped processor
   * @param rejectedInstructionListener step scoped skip listener
   * @param publisher event publisher
   * @param properties chunk size and skip limit
   * @return the step
   */
  @Bean
  public Step instructionStep(
      JobRepository jobRepository,
      PlatformTransactionManager transactionManager,
      FlatFileItemReader<RawInstruction> instructionReader,
      InstructionItemProcessor instructionProcessor,
      RejectedInstructionListener rejectedInstructionListener,
      IntakeEventPublisher publisher,
      IntakeProperties properties) {
    SkipListener<RawInstruction, PaymentInstruction> skipListener = rejectedInstructionListener;
    return new StepBuilder(IngestJob.INSTRUCTION_STEP, jobRepository)
        .<RawInstruction, PaymentInstruction>chunk(properties.chunkSize(), transactionManager)
        .reader(instructionReader)
        .processor(instructionProcessor)
        .writer(new InstructionKafkaWriter(publisher))
        .faultTolerant()
        .skipPolicy(new InstructionSkipPolicy(properties.skipLimit()))
        // A skipped row changes nothing transactional, so there is no need to roll back and
        // re-process the rest of its chunk.
        .noRollback(InstructionValidationException.class)
        .listener(skipListener)
        .listener(new InstructionStepListener())
        .build();
  }

  /**
   * Finalization step.
   *
   * @param jobRepository job repository
   * @param transactionManager transaction manager
   * @param jobExplorer reads earlier executions after a restart
   * @param registry file registry
   * @param publisher event publisher
   * @param clock clock
   * @param properties skip limit
   * @return the step
   */
  @Bean
  public Step finalizeStep(
      JobRepository jobRepository,
      PlatformTransactionManager transactionManager,
      JobExplorer jobExplorer,
      FileRegistryRepository registry,
      IntakeEventPublisher publisher,
      Clock clock,
      IntakeProperties properties) {
    return new StepBuilder(IngestJob.FINALIZE_STEP, jobRepository)
        .tasklet(
            new FinalizeTasklet(jobExplorer, registry, publisher, clock, properties),
            transactionManager)
        .build();
  }

  /**
   * Reads exactly {@code recordCount} rows after the header, so the trailer is never read. The
   * reader saves its position in the step context, which is what makes restarts resume.
   *
   * @param stagedPath job parameter
   * @param recordCount set by the validation step
   * @return the reader
   */
  @Bean
  @StepScope
  public FlatFileItemReader<RawInstruction> instructionReader(
      @Value("#{jobParameters['" + IngestJob.STAGED_PATH + "']}") String stagedPath,
      @Value("#{jobExecutionContext['" + IngestJob.RECORD_COUNT + "']}") Integer recordCount) {
    FlatFileItemReader<RawInstruction> reader =
        new FlatFileItemReaderBuilder<RawInstruction>()
            .name("instructionReader")
            .resource(new FileSystemResource(stagedPath))
            .encoding(StandardCharsets.UTF_8.name())
            .linesToSkip(1)
            .maxItemCount(recordCount)
            .lineMapper(new InstructionLineMapper())
            .strict(true)
            .build();
    // The default treats lines starting with '#' as comments; every line here is data.
    reader.setComments(new String[0]);
    return reader;
  }

  /**
   * Processor bound to this execution's batch.
   *
   * @param validator field and channel rules
   * @param clock clock
   * @param batchId from the job context
   * @param clientId job parameter
   * @param duplicateRefLines from the job context
   * @return the processor
   */
  @Bean
  @StepScope
  public InstructionItemProcessor instructionProcessor(
      InstructionValidator validator,
      Clock clock,
      @Value("#{jobExecutionContext['" + IngestJob.BATCH_ID + "']}") String batchId,
      @Value("#{jobParameters['" + IngestJob.CLIENT_ID + "']}") String clientId,
      @Value("#{jobExecutionContext['" + IngestJob.DUPLICATE_REF_LINES + "']}")
          String duplicateRefLines) {
    return new InstructionItemProcessor(
        validator, clock, UUID.fromString(batchId), clientId, duplicateRefLines);
  }

  /**
   * Skip listener bound to this execution's batch.
   *
   * @param publisher event publisher
   * @param batchId from the job context
   * @param clientId job parameter
   * @return the listener
   */
  @Bean
  @StepScope
  public RejectedInstructionListener rejectedInstructionListener(
      IntakeEventPublisher publisher,
      @Value("#{jobExecutionContext['" + IngestJob.BATCH_ID + "']}") String batchId,
      @Value("#{jobParameters['" + IngestJob.CLIENT_ID + "']}") String clientId) {
    return new RejectedInstructionListener(publisher, UUID.fromString(batchId), clientId);
  }
}
