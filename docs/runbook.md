# Remit runbook

Operational procedures. Commands assume the repository root and the ports used by Docker Compose; for Kubernetes, first run `kubectl -n remit port-forward deploy/intake-service 8081:8081`.

## Restart a failed batch job (FR-INT-06)

A batch job fails "technically" when something outside the file breaks while it runs: Kafka does not acknowledge a publish, MySQL is unreachable, the pod is stopped. Files that are simply invalid do not fail the job; they end in the SFTP `/error` directory with a `<file>.error.txt` note and need a corrected file from the client, not a restart (see [ADR 0007](decisions/0007-intake-failure-handling.md)).

When a job fails, intake-service leaves the remote file in `/processing` and keeps the local staged copy, and logs:

```
Job for PAY_ACME01_20261015093000.csv did not complete (execution 42: job FAILED: ...); file left in /processing for a restart, see docs/runbook.md
```

### 1. Find the execution

```bash
curl -s localhost:8081/actuator/ingestjobs | jq '.[] | select(.status == "FAILED")'
```

Each entry shows `executionId`, `fileName`, `status`, `exitCode` and `batchStatus`. The exit description (the error) is in the JSON log line above, or in MySQL:

```sql
SELECT JOB_EXECUTION_ID, STATUS, EXIT_MESSAGE FROM remit_intake.BATCH_JOB_EXECUTION
WHERE STATUS = 'FAILED' ORDER BY JOB_EXECUTION_ID DESC LIMIT 5;
```

### 2. Fix the cause

Make sure Kafka and MySQL are healthy (`docker compose ps`, or `curl -s localhost:8081/actuator/health`).

### 3. Restart it

```bash
curl -s -X POST localhost:8081/actuator/ingestjobs/42 | jq
```

The call runs the job again with its original parameters and returns when it finishes:

```json
{"executionId": 43, "fileName": "PAY_ACME01_20261015093000.csv", "disposition": "PROCESSED", "reasonCode": null, "detail": null}
```

What happens:

- Steps that completed (for example `fileValidationStep`) are not run again, so the batch keeps its batch id and registry row.
- `instructionStep` resumes after its last committed chunk (100 rows per chunk). Rows of the chunk that failed may be published a second time; routing-service drops them by idempotency key.
- `finalizeStep` totals the counts of every execution and publishes the final batch event.
- The remote file is moved from `/processing` to `/processed` (or to `/error` if the batch ended FAILED), exactly as after a normal run.

`disposition: "INCOMPLETE"` means the restart failed as well; check the logs, fix, and restart the new execution id.

### Alternative: drop the file again

Moving the file from `/processing` back to `/inbound` has the same effect. The poller stages it under the same content hash, launches the job with identical parameters, and Spring Batch restarts the failed job instance. This also works when the local staged copy is gone (for example the pod was replaced and its `emptyDir` was lost), because the poller downloads the file again.

```bash
sftp -P 2222 remit@localhost <<'EOF'
rename /processing/PAY_ACME01_20261015093000.csv /inbound/PAY_ACME01_20261015093000.csv
EOF
```

### A job stuck in STARTED

If intake-service was killed while a job ran, the execution stays `STARTED` and cannot be restarted until it is marked failed. With intake-service running (so nothing else is executing it), run against `remit_intake`, replacing 42:

```sql
UPDATE BATCH_STEP_EXECUTION SET STATUS = 'FAILED', EXIT_CODE = 'FAILED', END_TIME = NOW(6)
WHERE JOB_EXECUTION_ID = 42 AND STATUS IN ('STARTING', 'STARTED');
UPDATE BATCH_JOB_EXECUTION SET STATUS = 'FAILED', EXIT_CODE = 'FAILED', END_TIME = NOW(6)
WHERE JOB_EXECUTION_ID = 42;
```

Then restart it as in step 3.
