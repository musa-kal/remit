# Remit runbook

Operational procedures. Commands assume the repository root and the ports used by Docker Compose; for Kubernetes, first run `kubectl -n remit port-forward deploy/intake-service 8081:8081`.

## Restart a service

Restart a service after changing its configuration, to recover it when its health stays `DOWN` after the cause is fixed (for example MySQL was restarted underneath it), or to pick up a rotated secret.

```bash
scripts/restart.sh routing-service            # compose: docker compose restart, then waits for the healthcheck
scripts/restart.sh api-service k8s            # kubectl -n remit rollout restart + rollout status
```

The script exits non-zero if the service is not healthy within 180 s (`--timeout` to change). What a restart does to work in flight:

| Service | Effect of a restart |
|---|---|
| intake-service | A job that was running is interrupted and its file stays in `/processing`; restart it as in [Restart a failed batch job](#restart-a-failed-batch-job-fr-int-06), after [marking it failed](#a-job-stuck-in-started) if it is left `STARTED`. Restart intake-service between polls (`/actuator/ingestjobs` shows nothing `STARTED`) to avoid this. Files still in `/inbound` are picked up by the next poll. |
| routing-service | Uncommitted offsets are redelivered to the next consumer. An instruction caught between its `ROUTING` insert and its commit is finished on redelivery; one already routed is answered with a `DUPLICATE` status event ([ADR 0002](decisions/0002-idempotency.md)). |
| api-service | Tokens stay valid (they are stateless), but the in-memory login lockout is cleared. Unconsumed events are read after the restart; the read model and the audit trail ignore redeliveries. |

`docker compose restart` keeps the container and its environment. After changing `.env`, recreate the container instead so it sees the new values: `docker compose up -d <service>`, then `scripts/deploy.sh compose` to wait for health.

Check the result:

```bash
docker compose ps                              # STATUS shows (healthy)
curl -s localhost:8082/actuator/health | jq    # components db and kafka UP (8080 api, 8081 intake)
scripts/logs.sh -n 20 --no-follow routing-service
```

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

## Inspect the DLT

routing-service dead-letters an instruction to `payments.instructions.DLT` after three failed attempts (1 s and 2 s back-off), at once for a channel rule violation, and at once for a record it cannot deserialize. Each dead-lettered instruction also gets a `DEAD_LETTERED` event on `payments.status-events`; a poison pill does not, because it has no readable key. [ADR 0003](decisions/0003-dlt-and-replay.md) lists every header.

### Count and list the records

With Docker Compose running (the paths are inside the `kafka` container; on Git Bash for Windows prefix the commands with `MSYS_NO_PATHCONV=1`):

```bash
# Records per partition (end offsets)
docker compose exec kafka /opt/kafka/bin/kafka-get-offsets.sh \
  --bootstrap-server localhost:9092 --topic payments.instructions.DLT

# Key and payload of every record, with partition and offset
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic payments.instructions.DLT \
  --from-beginning --timeout-ms 5000 \
  --property print.partition=true --property print.offset=true --property print.key=true
```

The key is the original record key (the debtor account) and the value is the original instruction JSON, or the original bytes of an unreadable record.

### Why did it fail?

Add the headers. `kafka_dlt-exception-stacktrace` holds a multi-line stack trace; the `grep` drops its lines and keeps the rest:

```bash
docker compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic payments.instructions.DLT \
  --from-beginning --timeout-ms 5000 \
  --property print.partition=true --property print.offset=true \
  --property print.key=true --property print.headers=true \
  | grep -a -Ev '^([[:space:]]|Caused by)'
```

Read `kafka_dlt-exception-cause-fqcn` and `kafka_dlt-exception-message` first:

| Cause | Meaning | Action |
|---|---|---|
| `ca.musakaleem.remit.routing.error.RoutingException` | Retried three times and still failing: Kafka did not acknowledge a publish, or the chaos hook (`remittanceInfo` = `FORCE_FAIL`, local and docker profiles only) | Fix the cause (check `/actuator/health` on port 8082), then replay |
| `ca.musakaleem.remit.routing.error.NonRetryableRoutingException` | The instruction breaks a channel rule (for example INTERAC in USD) or lacks a required field | Do not replay; the client must send a corrected instruction |
| `org.springframework.kafka.support.serializer.DeserializationException` | The record is not a readable `PaymentInstruction` (malformed JSON or an untrusted `__TypeId__`) | Do not replay; find the producer that wrote it (see `kafka_dlt-original-*` and `eventId`) |

`kafka_dlt-original-partition` and `kafka_dlt-original-offset` are binary (4-byte int and 8-byte long), so the console consumer prints them as unreadable characters; the DLT record's own partition equals the original partition.

### Check the routing ledger

The ledger row of a dead-lettered instruction stays `ROUTING`; `attempts` is 3 after retries and 1 after a rule violation. Replace the key:

```bash
docker compose exec mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e \
  "SELECT status, attempts, updated_at FROM remit_routing.processed_instruction
   WHERE idempotency_key = \"<idempotencyKey>\""'
```

### Replay

Replay goes through api-service; see [Replay a DLT message](#replay-a-dlt-message).

## Replay a DLT message

api-service stores every record of `payments.instructions.DLT` as a dead letter and replays it on request: it republishes the stored instruction to `payments.instructions` with `replayCount + 1`, exactly as intake-service would publish it, and routing-service processes it again. Only the ADMIN role can replay, each dead letter at most 3 times, and every replay is audited with the administrator's username. [ADR 0003](decisions/0003-dlt-and-replay.md) and [ADR 0010](decisions/0010-api-read-model.md#dead-letters) have the details.

### 1. Find the dead letter

```bash
set -a; . ./.env; set +a
TOKEN=$(curl -s -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$REMIT_ADMIN_PASSWORD\"}" | jq -r .accessToken)
curl -s "localhost:8080/api/v1/dead-letters?status=PENDING" -H "Authorization: Bearer $TOKEN" \
  | jq '.content[] | {id, idempotencyKey, exceptionClass, exceptionMessage, replayCount, replayable}'
```

### 2. Decide whether a replay can succeed

Read `exceptionClass` as in [Why did it fail?](#why-did-it-fail): replay only after fixing a temporary cause (`RoutingException`). A `NonRetryableRoutingException` fails again, and an unreadable record (`idempotencyKey` is `null`, `replayable` is `false`) cannot be replayed at all.

The chaos hook fails every attempt of a `FORCE_FAIL` instruction while `remit.routing.chaos-enabled` is true (the `local` and `docker` profiles), so replaying one in compose fails again by design; that is how the demo reaches the cap.

### 3. Replay

```bash
curl -s -X POST localhost:8080/api/v1/dead-letters/1/replay -H "Authorization: Bearer $TOKEN" | jq
```

`202` with `"status": "REPLAY_REQUESTED"` and the new `replayCount` means the instruction is back on `payments.instructions`. Other answers:

| Status | Meaning |
|---|---|
| 409 `A replay of dead letter 1 is already pending` | Wait for routing-service's outcome (a few seconds; a failing replay takes about 3 s of retries) |
| 409 `... reached the limit of 3 replays` | The dead letter is now EXHAUSTED; the client has to send a corrected instruction |
| 409 `... was already replayed successfully` | Nothing to do |
| 409 `... holds an unreadable record` | Poison pill; find the producer that wrote it |
| 404 | Wrong id |
| 503 | Kafka did not acknowledge; nothing changed, try again |

### 4. Check the outcome

```bash
curl -s "localhost:8080/api/v1/dead-letters?status=REPLAYED" -H "Authorization: Bearer $TOKEN" | jq '.content[].id'
curl -s "localhost:8080/api/v1/instructions/<idempotencyKey>" -H "Authorization: Bearer $TOKEN" \
  | jq '{status, replayCount, history: [.history[] | .status]}'
```

After a successful replay the dead letter is `REPLAYED` and the instruction's history ends with `REPLAYED`, `ROUTED` (or `REQUIRES_REVIEW`). After a failed one the dead letter is `PENDING` again, or `EXHAUSTED` after the third replay, and the history ends with another `DEAD_LETTERED`. The audit trail shows who replayed it:

```bash
curl -s "localhost:8080/api/v1/audit-events?entityId=<idempotencyKey>&eventType=DLT_REPLAY_REQUESTED" \
  -H "Authorization: Bearer $TOKEN" | jq '.content[] | {actor, occurredAt, details}'
```

## Rotate the JWT secret

api-service signs tokens with one HS256 key, `REMIT_JWT_SECRET` ([ADR 0004](decisions/0004-jwt-approach.md)). Rotate it when it may have leaked, and as routine hygiene. Rotation invalidates every issued token immediately: callers get 401 `The access token is invalid` and must log in again, at most 15 minutes of tokens are affected.

1. Generate a new secret of at least 32 bytes:

   ```bash
   openssl rand -base64 48
   ```

2. Replace the value:
   - compose or a local run: set `REMIT_JWT_SECRET` in `.env`;
   - Kubernetes: update the `remit` Secret (`scripts/k8s-secrets.sh` rebuilds it from `.env`).
3. Restart every api-service instance so they all sign and verify with the new key:
   - compose: `docker compose up -d api-service` (recreates the container with the new environment);
   - Kubernetes: `kubectl -n remit rollout restart deployment/api-service` and wait for `kubectl -n remit rollout status deployment/api-service`;
   - local jar: stop it and start it again after `set -a; . ./.env; set +a`.
4. Check: `curl -s localhost:8080/actuator/health/readiness` is `UP`, a login returns a token, and an old token now gets 401.

If api-service does not start, its log says why: `REMIT_JWT_SECRET is not set` or `REMIT_JWT_SECRET is N bytes long; HS256 needs at least 32 bytes`.
