# ADR 0007: How intake-service fails a file, a batch or a job

- Status: accepted
- Date: 2026-09-30

## Context

Spec 3.4, 3.5 and FR-INT-02 to FR-INT-06 describe three different kinds of failure. They need different outcomes: a bad file must not be retried forever, a batch with too many bad rows must be visible as FAILED, and a broker outage must not lose the file.

## Decision

**File-level rejection** (INVALID_FILENAME, INVALID_ENCODING, INVALID_HEADER, INVALID_TRAILER, RECORD_COUNT_MISMATCH, CONTROL_TOTAL_MISMATCH, EMPTY_FILE, DUPLICATE_FILE):

- A badly named file is moved from `/inbound` to `/error` by the poller before any download or job. There is no client id, so there is no registry row and no event.
- Every other file-level check runs in `fileValidationStep`. The step ends with exit code `REJECTED` and the job flow ends there with job status COMPLETED (exit code REJECTED). The job is therefore not restartable, which is right: rerunning a bad file cannot fix it.
- The file gets a `file_registry` row with status FAILED and the reason code, and a `BatchEvent(FAILED)` carrying the extra header `reasonCode` is published. A later file with the same content is then a DUPLICATE_FILE.
- A duplicate file gets no registry row (the unique `file_hash` already belongs to the first file). Its FAILED batch event uses a fresh batch id so api-service can still show the attempt.
- Dropping a file whose name and content were already processed hits Spring Batch's `JobInstanceAlreadyCompleteException`; it is treated as DUPLICATE_FILE too.
- The remote file goes to `/error` with `<file>.error.txt`: the reason code on line one, a detail line after it.

**Batch-level failure** (spec 3.5, REJECTION_THRESHOLD_EXCEEDED and SKIP_LIMIT_EXCEEDED):

- Rows are validated and published chunk by chunk (100 rows per chunk). More than 50% rejected rows can only be known at the end, so `finalizeStep` decides the status and publishes `BatchEvent(FAILED)`. Valid rows published before that verdict stay published; the FAILED batch event tells downstream consumers the batch as a whole failed.
- `remit.intake.skip-limit` is also enforced during the step: when a row would be the (limit + 1)th skip, the step stops, `InstructionStepListener` sets exit code `SKIP_LIMIT_EXCEEDED`, and the flow still goes to `finalizeStep`, which records FAILED. The chunk that was in flight rolls back, so its valid rows are not published; its skipped rows were already published as rejections.
- These files go to `/error` with the reason code, the same as file-level rejections.

**Technical failure** (Kafka unreachable, database error, SFTP error during the job):

- The step fails and the job ends FAILED. Nothing is moved: the remote file stays in `/processing` and the staged copy stays in the staging directory.
- The job is restarted with the same parameters (see `docs/runbook.md`). Spring Batch skips completed steps and the reader resumes after the last committed chunk. Instructions of the failed chunk that reached Kafka before the failure are sent again, which is safe because routing-service deduplicates by idempotency key (at-least-once delivery).
- `finalizeStep` sums the instruction step counts over every execution of the job instance, so a restarted batch reports the true accepted and rejected totals.

## Consequences

- Operators see every outcome in api-service through the batch events, and on the SFTP server through `/processed` and `/error`.
- A batch can be FAILED while some of its instructions were routed. Holding all publishing until the end would need a staging table or a transactional outbox; that is out of scope (see stretch item S4).
- A job that crashed without recording FAILED (for example the JVM was killed) stays STARTED in the repository and must be marked FAILED before it can be restarted; the runbook has the SQL.
