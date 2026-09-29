# ADR 0014: Components of the intake job and the SFTP poller

- Status: accepted
- Date: 2026-09-30

## Context

FR-INT-01 asks for SFTP polling with Spring Integration SFTP (`DefaultSftpSessionFactory`) or an equivalent library. FR-INT-04 describes `instructionStep` as `FlatFileItemReader` -> `ValidatingItemProcessor` producing `PaymentInstruction` -> `KafkaItemWriter` (or a custom writer using `KafkaTemplate`), with a `SkipPolicy` and a `SkipListener` for invalid rows. Section 5 requires five headers (`eventId`, `eventType`, `occurredAt`, `correlationId`, `schemaVersion`) on every event.

## Decision

### Processor: a plain `ItemProcessor`, not `ValidatingItemProcessor`

`InstructionItemProcessor implements ItemProcessor<RawInstruction, PaymentInstruction>` validates a row with `InstructionValidator` and maps it to a `PaymentInstruction` in one place. Spring Batch's `ValidatingItemProcessor<T>` returns the same type it receives, so it could only validate the `RawInstruction`; producing the `PaymentInstruction` would need a second processor and a `CompositeItemProcessor`, plus an adapter from `InstructionValidator` (which returns every failed rule as a list of reason codes) to Spring's `Validator` (which throws one `ValidationException`). The custom processor throws `InstructionValidationException` carrying all reason codes, which is what the rejection event needs. The spec's intent (validate each row, skip the invalid ones through a `SkipPolicy`, publish them from a `SkipListener`) is kept: `InstructionSkipPolicy` and `RejectedInstructionListener` do exactly that.

### Writer: a custom writer on `KafkaTemplate`, not `KafkaItemWriter`

The spec allows either. `KafkaItemWriter` (Spring Batch 5.2) sends each item with `KafkaTemplate.sendDefault(key, value)`, which cannot attach record headers, so the five standard headers would be missing. `InstructionKafkaWriter` sends each instruction through `IntakeEventPublisher.publishInstruction` (topic `payments.instructions`, key = debtor account, headers from `EventHeaders.record`) and then waits for every acknowledgement of the chunk (`IntakeEventPublisher.awaitAll`), so the chunk commits only after Kafka has acknowledged all of its records. That is the same "send all, then wait" behaviour as `KafkaItemWriter`'s flush.

### Poller: `@Scheduled` plus a small gateway, not an inbound channel adapter

`SftpPoller` is a `@Scheduled(fixedDelay)` method that lists `/inbound` through `SftpFileGateway`, and `InboundFileProcessor` performs the FR-INT-02 steps explicitly: download to staging, move to `/processing`, run the job, move to `/processed` or `/error` with a note. `SpringIntegrationSftpFileGateway` implements the gateway with Spring Integration's `SftpRemoteFileTemplate` over a caching `DefaultSftpSessionFactory` (Apache MINA SSHD), so the spec's library is used. Spring Integration's SFTP inbound channel adapter was not used: its synchronizer copies remote files into a local directory and tracks them with filters and a metadata store, and the remote moves, the error note and the "leave the file in `/processing` for a restart" rule would then live in adapter callbacks rather than in one readable class. The gateway interface also keeps the job independent of the transport (see "Extending intake with a new channel" in [docs/architecture.md](../architecture.md#extending-intake-with-a-new-channel-as2-or-connectdirect)) and lets tests replace SFTP.

## Consequences

- The processor and the writer are ordinary classes (`InstructionItemProcessorTest` covers the processor, the job integration tests cover the writer against Kafka); `IngestJobConfig` wires them with the standard chunk, skip and listener APIs.
- Anyone who reads FR-INT-04 literally will look for `ValidatingItemProcessor` and `KafkaItemWriter` and not find them; this ADR explains why.
- Polling is single-threaded and processes one file at a time. That fits the file sizes in scope (10,000 rows in about 3 s of job time); parallel files would need a second job launcher thread and care with the per-file MDC.
