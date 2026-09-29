# Remit runbook

Operational procedures for the local Docker Compose stack and the local Kubernetes (kind) deployment. The design behind them is in [architecture.md](architecture.md) and the [decision records](decisions/README.md).

## Contents

- [Conventions](#conventions)
- [Restart a service](#restart-a-service)
  - [With Docker Compose](#with-docker-compose)
  - [On Kubernetes](#on-kubernetes)
- [Restart a failed batch job (FR-INT-06)](#restart-a-failed-batch-job-fr-int-06)
- [Inspect the DLT](#inspect-the-dlt)
- [Replay a DLT message](#replay-a-dlt-message)
- [Rotate the JWT secret](#rotate-the-jwt-secret)
- [Rotate other secrets](#rotate-other-secrets)
  - [Rotate secrets in Docker Compose](#rotate-secrets-in-docker-compose)
  - [Rotate secrets in Kubernetes](#rotate-secrets-in-kubernetes)
- [Reset the local stack](#reset-the-local-stack)
- [Common failure signatures](#common-failure-signatures)
- [Recover from a login lockout](#recover-from-a-login-lockout)
- [Run the CI pipeline and refresh its evidence](#run-the-ci-pipeline-and-refresh-its-evidence)

## Conventions

- Commands run from the repository root in Bash (Git Bash on Windows), with `.env` filled in. Many examples load it first: `set -a; . ./.env; set +a`.
- Ports are the compose defaults: api-service `localhost:8080`, intake-service `localhost:8081`, routing-service `localhost:8082`, MySQL `localhost:3307`.
- On the local Kubernetes deployment (`scripts/k8s-up.sh`) the API is at the same `localhost:8080`. For intake-service's actuator (`localhost:8081` below), first run `kubectl -n remit port-forward deploy/intake-service 8081:8081`, and replace `docker compose exec <service>` with `kubectl -n remit exec <pod or deploy/name>` (for example `kubectl -n remit exec -it kafka-0 -- ...`, `kubectl -n remit exec -it mysql-0 -- ...`).
- On Git Bash for Windows, prefix `docker compose exec` commands that pass absolute container paths with `MSYS_NO_PATHCONV=1`.
- Logs are JSON; `scripts/logs.sh <service> [compose|k8s]` follows them pretty-printed (`--no-follow -n 50` for the last lines). Filter one batch or instruction with jq, for example `scripts/logs.sh -c --no-follow intake-service | jq 'select(.correlationId == "<batch id>")'`.

## Restart a service

Restart a service after changing its configuration, to recover it when its health stays `DOWN` after the cause is fixed (for example MySQL was restarted underneath it), or to pick up a rotated secret.

### With Docker Compose

```bash
scripts/restart.sh routing-service            # docker compose restart, then waits for the healthcheck
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

### On Kubernetes

`scripts/restart.sh <service> k8s` runs `kubectl -n remit rollout restart deployment/<service>` and waits for `rollout status` (180 s, `--timeout` to change), then lists the service's pods. It uses the context `kind-remit`; set `REMIT_KUBE_CONTEXT` for another cluster (empty for the current context). How each deployment replaces its pods:

| Service | Strategy | During the restart |
|---|---|---|
| intake-service | `Recreate` | The old pod stops before the new one starts, so there is never a second poller. No polling until the new pod is ready (30 to 60 s); a job in flight is interrupted as in the table above, and its staged copy (an `emptyDir`) is gone, so restart it with [Alternative: drop the file again](#alternative-drop-the-file-again). |
| routing-service | rolling, `maxSurge: 1`, `maxUnavailable: 0` | One new pod at a time; the partitions are rebalanced between the running consumers, so routing continues. A PodDisruptionBudget keeps at least one pod through node drains. |
| api-service | rolling, `maxSurge: 1`, `maxUnavailable: 0` | The new pod takes traffic once ready, then the old one stops; the NodePort keeps answering. |

Pods read `remit-config` and `remit-secrets` only at startup, so a restart is also how a changed ConfigMap or Secret takes effect (see [Rotate secrets in Kubernetes](#rotate-secrets-in-kubernetes)).

Check the result:

```bash
kubectl -n remit get pods                      # READY 1/1, RESTARTS 0 for the new pods
kubectl -n remit describe deployment/routing-service | tail -n 20   # events if a rollout hangs
scripts/logs.sh -n 20 --no-follow routing-service k8s
```

A rollout that does not finish usually means the new pod fails its startup probe (up to 200 s) or waits in its `wait-for-dependencies` init container for MySQL, MongoDB, Kafka or SFTP: `kubectl -n remit logs deploy/<service> -c wait-for-dependencies`. `kubectl -n remit rollout undo deployment/<service>` returns to the previous pod template.

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

Each entry shows `executionId`, `fileName`, `status`, `exitCode` and `batchStatus`. The exit description (the error) is in the JSON log line above, or in the Spring Batch metadata tables (next section).

### Spring Batch metadata queries

The job repository lives in `remit_intake`. Open a MySQL shell as root (the `remit_intake` user can read the tables too, but the `UPDATE` in [A job stuck in STARTED](#a-job-stuck-in-started) is easiest as root):

```bash
docker compose exec mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" remit_intake'
# Kubernetes: kubectl -n remit exec -it mysql-0 -- sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" remit_intake'
```

Recent executions with their file and outcome:

```sql
SELECT e.JOB_EXECUTION_ID, e.JOB_INSTANCE_ID, e.STATUS, e.EXIT_CODE, e.START_TIME, e.END_TIME,
       p.PARAMETER_VALUE AS FILE_NAME
FROM BATCH_JOB_EXECUTION e
JOIN BATCH_JOB_EXECUTION_PARAMS p
  ON p.JOB_EXECUTION_ID = e.JOB_EXECUTION_ID AND p.PARAMETER_NAME = 'fileName'
ORDER BY e.JOB_EXECUTION_ID DESC LIMIT 10;
```

Why one failed:

```sql
SELECT JOB_EXECUTION_ID, STATUS, EXIT_MESSAGE FROM BATCH_JOB_EXECUTION
WHERE STATUS = 'FAILED' ORDER BY JOB_EXECUTION_ID DESC LIMIT 5;
```

How far each step got (replace 42). `READ_COUNT` and `WRITE_COUNT` of `instructionStep` show how many rows were read and published in committed chunks (100 rows each); a restart resumes after the last committed chunk:

```sql
SELECT STEP_NAME, STATUS, EXIT_CODE, READ_COUNT, WRITE_COUNT, PROCESS_SKIP_COUNT,
       COMMIT_COUNT, ROLLBACK_COUNT, START_TIME, END_TIME
FROM BATCH_STEP_EXECUTION WHERE JOB_EXECUTION_ID = 42 ORDER BY STEP_EXECUTION_ID;
```

Every execution of the same file (one job instance per file name and content hash; a restart adds an execution to the same instance):

```sql
SELECT e.JOB_EXECUTION_ID, e.STATUS, e.EXIT_CODE, e.START_TIME
FROM BATCH_JOB_EXECUTION e
WHERE e.JOB_INSTANCE_ID = (SELECT JOB_INSTANCE_ID FROM BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = 42)
ORDER BY e.JOB_EXECUTION_ID;
```

The file registry row of the batch (`status` is `RECEIVED` until `finalizeStep` ran):

```sql
SELECT batch_id, file_name, status, reason_code, received_at, completed_at FROM file_registry
WHERE file_name = 'PAY_ACME01_20261015093000.csv';
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

### What to watch while it runs

A replay takes a few seconds when it succeeds and about 3 s of retries when it fails again. Follow it in three places:

```bash
scripts/logs.sh -c routing-service | jq -c 'select(.idempotencyKey == "<idempotencyKey>") | {message, level}'
scripts/logs.sh -c api-service | jq -c 'select(.idempotencyKey == "<idempotencyKey>" or (.message | test("replayed dead letter"; "i"))) | {message, level}'
```

| Where | Success | Failure again |
|---|---|---|
| api-service log | `Replayed dead letter <id> as replay <n> of 3`, then `Instruction status REPLAYED` and `Instruction status ROUTED` | `Replayed dead letter ...`, then `Dead letter <id> from payments.instructions-<p>@<offset>: ...` and `Instruction status DEAD_LETTERED` |
| routing-service log | `Resuming an unfinished instruction, attempt <n>`, then `Routed EFT ... (debtor account ***1234, replay <n>)` | `Resuming an unfinished instruction ...` on each attempt, then `Instruction dead-lettered: <cause>` |
| `payments.status-events` | `REPLAYED` (detail `replay <n>`), then `ROUTED` or `REQUIRES_REVIEW` | `DEAD_LETTERED` |
| `payments.instructions.DLT` | nothing new | one new record whose payload carries `"replayCount": <n>` |
| `GET /dead-letters/{id}` state | `REPLAYED` | `PENDING`, or `EXHAUSTED` after the third replay |

To see the status events themselves (keyed by idempotency key):

```bash
MSYS_NO_PATHCONV=1 docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh   --bootstrap-server localhost:9092 --topic payments.status-events --from-beginning --timeout-ms 5000   --property print.key=true | grep '<idempotencyKey>'
```

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

api-service signs tokens with one HS256 key, `REMIT_JWT_SECRET` ([ADR 0004](decisions/0004-jwt-approach.md)). Rotate it when it may have leaked, and as routine hygiene. Rotation invalidates every issued token immediately: callers get 401 `The access token is invalid` and must log in again. Tokens live 15 minutes, so nothing older is affected, and there is no refresh token to revoke.

Generate a new secret of at least 32 bytes:

```bash
openssl rand -base64 48
```

### Docker Compose (or a local jar)

1. Set `REMIT_JWT_SECRET` in `.env`.
2. Recreate api-service so it reads the new environment (a plain `restart` keeps the old one):

   ```bash
   docker compose up -d api-service
   scripts/deploy.sh compose             # waits until every service is healthy again
   ```

   For a local jar: stop it and start it again after `set -a; . ./.env; set +a`.

### Kubernetes

1. Set `REMIT_JWT_SECRET` in `.env`.
2. Rebuild the Secret from `.env` and restart the services that read it:

   ```bash
   scripts/k8s-secrets.sh --restart      # kubectl apply of remit-secrets, then rollout restart + status
   ```

   Or by hand: `scripts/k8s-secrets.sh`, then `kubectl -n remit rollout restart deployment/api-service` and `kubectl -n remit rollout status deployment/api-service`. Every api-service replica must restart, or replicas would disagree about which tokens are valid.

### Check

- `curl -s localhost:8080/actuator/health/readiness` is `UP`.
- A login returns a token, and a call with it answers 200.
- A token issued before the rotation now gets 401.

If api-service does not start, its log says why: `REMIT_JWT_SECRET is not set` or `REMIT_JWT_SECRET is N bytes long; HS256 needs at least 32 bytes`. On Kubernetes the pod then crash-loops and the rollout does not finish; `kubectl -n remit logs deploy/api-service` shows the message, and `kubectl -n remit rollout undo deployment/api-service` does not help because the Secret, not the pod template, changed: fix `.env` and run `scripts/k8s-secrets.sh --restart` again.

## Rotate other secrets

Every other secret is a password of MySQL, MongoDB or SFTP. Those servers read the values from the environment only when their data volume is empty, so a rotation has two parts: change the password inside the server, then give the services the new value.

| Variable | Read by | Notes |
|---|---|---|
| `MYSQL_ROOT_PASSWORD` | MySQL (init), the healthcheck, the runbook's SQL shells | not used by the services |
| `REMIT_INTAKE_DB_PASSWORD`, `REMIT_ROUTING_DB_PASSWORD`, `REMIT_API_DB_PASSWORD` | MySQL users `remit_intake`, `remit_routing`, `remit_api` (init) and each service | |
| `MONGO_ROOT_PASSWORD` | MongoDB (init) | not used by the services |
| `REMIT_MONGO_PASSWORD` | MongoDB user `remit_audit` (init) and api-service | URL-safe characters only |
| `REMIT_SFTP_PASSWORD` | SFTP user (re-created on every container start) and intake-service | |
| `REMIT_OPERATOR_PASSWORD`, `REMIT_ADMIN_PASSWORD` | api-service user seeding only | an existing user keeps its password; changing it means updating the BCrypt hash in `remit_api.app_user` or a fresh database |

On a local stack the simplest rotation is a reset with the new values in `.env` ([Reset the local stack](#reset-the-local-stack)); it deletes all data.

### Rotate secrets in Docker Compose

Keeping the data, for one database user (here `remit_api`):

```bash
NEW='new-password'    # then put the same value in .env as REMIT_API_DB_PASSWORD
docker compose exec mysql sh -c "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -e \"ALTER USER 'remit_api'@'%' IDENTIFIED BY '$NEW'\""
docker compose up -d api-service && scripts/deploy.sh compose
```

- MongoDB application user: `docker compose exec mongo mongosh -u root -p --authenticationDatabase admin --eval "db.getSiblingDB('remit_audit').changeUserPassword('remit_audit', '<new>')"`, update `REMIT_MONGO_PASSWORD` in `.env`, then `docker compose up -d api-service`.
- SFTP: update `REMIT_SFTP_PASSWORD` in `.env`, then `docker compose up -d sftp intake-service` (the container re-creates the user from `SFTP_USERS` on start; its host key changes too, which intake-service accepts).
- Root passwords: change them in the server the same way (`ALTER USER` for both `'root'@'%'` and `'root'@'localhost'`, `changeUserPassword('root', ...)` in the `admin` database), then update `.env` so the healthchecks keep working (`docker compose up -d mysql mongo`).

### Rotate secrets in Kubernetes

On Kubernetes every secret lives in the Secret `remit-secrets` (namespace `remit`); `k8s/base/secret.example.yaml` lists its keys. It is never edited by hand or committed: `scripts/k8s-secrets.sh` builds it from `.env` and applies it (`kubectl apply`, so it creates or replaces it). The values are never printed or passed on a command line.

1. Change the value in `.env` (for example `openssl rand -base64 48` for `REMIT_JWT_SECRET`).
2. Apply the Secret and restart the services, which only read it at startup:

   ```bash
   scripts/k8s-secrets.sh --restart     # kubectl apply, then rollout restart and rollout status of the three services
   ```

   Without `--restart`, restart them yourself: `kubectl -n remit rollout restart deployment` (all of them) or `scripts/restart.sh <service> k8s`.
3. Check: `kubectl -n remit get pods` shows new pods ready, and a login through `http://localhost:8080/api/v1/auth/login` works.

What a rotation affects:

| Key | Effect |
|---|---|
| `REMIT_JWT_SECRET` | Every issued token becomes invalid at once, as in [Rotate the JWT secret](#rotate-the-jwt-secret). |
| `REMIT_OPERATOR_PASSWORD`, `REMIT_ADMIN_PASSWORD` | Only used to seed missing users; existing users keep their password. |
| `MYSQL_ROOT_PASSWORD`, `REMIT_*_DB_PASSWORD`, `MONGO_ROOT_*`, `REMIT_MONGO_*`, `REMIT_SFTP_*` | Read by MySQL, MongoDB and SFTP only when their volumes are empty. Change the password inside the server first (for example `ALTER USER 'remit_api'@'%' IDENTIFIED BY '...'` as root in `kubectl -n remit exec -it mysql-0 -- mysql -uroot -p`), then update the Secret and restart the services. On the local cluster the simpler way is a fresh stack: `scripts/k8s-down.sh --keep-cluster`, then `scripts/k8s-up.sh` (this deletes all data). The SFTP pod re-creates its user from the Secret on every start: `kubectl -n remit rollout restart deployment/sftp`, then restart intake-service. |

## Reset the local stack

The samples are accepted once per database (duplicate file detection), so a repeat demo, a second smoke test or a password change in `.env` needs empty volumes. Every reset below deletes all batches, the audit trail, the Kafka topics and the SFTP directories.

| Goal | Docker Compose | Kubernetes (kind) |
|---|---|---|
| Stop, keep the data | `docker compose down` | `kubectl -n remit scale deployment --all --replicas=0` (scale back with `scripts/k8s-up.sh`) |
| Empty every volume and start again | `docker compose down -v`, then `scripts/deploy.sh compose` | `scripts/k8s-down.sh --keep-cluster`, then `scripts/k8s-up.sh` (about 1.5 minutes, the images stay loaded) |
| Fresh stack plus the samples, checked | `scripts/smoke-test.sh --reset --keep` | `scripts/k8s-down.sh --keep-cluster && scripts/k8s-up.sh && scripts/smoke-test.sh --target k8s` |
| Remove everything | `docker compose down -v --rmi local` | `scripts/k8s-down.sh` (deletes the cluster) |

Notes:

- `.env` is read again on `up`, and the init scripts in `docker/` (schemas, users, MongoDB indexes, topics) run again on the empty volumes, so a reset is also the way to apply changed database passwords.
- The CI stack is separate: `ci/docker-compose.jenkins.yml` and its `remit-ci_*` volumes are not touched by any of the commands above.
- Rebuilding only the api-service read model (possible because every projection is idempotent, [ADR 0010](decisions/0010-api-read-model.md)): stop api-service (`docker compose stop api-service`), reset its consumer group with `MSYS_NO_PATHCONV=1 docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group api-service --reset-offsets --to-earliest --all-topics --execute`, and start it again (`docker compose up -d api-service`). Existing rows and audit documents stay as they are and redelivered events change nothing; to rebuild from scratch, truncate the `remit_api` read model tables (not `app_user`, `app_user_role` or `flyway_schema_history`) before starting it. This procedure is not exercised by the automated tests.

## Common failure signatures

Search the JSON logs (`scripts/logs.sh -n all --no-follow <service>`) for these messages.

| Service | Log message (or its start) | Meaning | What to do |
|---|---|---|---|
| intake | `SFTP poll of /inbound failed: ...` | The poller could not list the directory (SFTP down, wrong password, network) | Check `docker compose ps sftp` and `REMIT_SFTP_*`; the next poll retries on its own |
| intake | `Rejecting <file>: name does not match PAY_<CLIENTID>_<yyyyMMddHHmmss>.csv` | Bad file name; the file went to `/error` with `INVALID_FILENAME` | The client must rename and resend |
| intake | `Rejected file: <REASON> (<detail>)`, then `File <file> moved to error: <REASON>` | File-level rejection (`DUPLICATE_FILE`, `INVALID_HEADER`, `INVALID_TRAILER`, `RECORD_COUNT_MISMATCH`, `CONTROL_TOTAL_MISMATCH`, `EMPTY_FILE`, `INVALID_ENCODING`); the second line alone also follows a batch FAILED by the rejection threshold | Nothing to restart; read `<file>.error.txt` in `/error` and ask for a corrected file ([ADR 0007](decisions/0007-intake-failure-handling.md)) |
| intake | `File <file> with the same content was already processed` | The same name and content ran before (`JobInstanceAlreadyCompleteException`), treated as `DUPLICATE_FILE` | None |
| intake | `Job for <file> did not complete (execution N: ...); file left in /processing for a restart` | Technical failure during the job (Kafka or MySQL) | [Restart a failed batch job](#restart-a-failed-batch-job-fr-int-06) |
| intake | `Could not run the ingest job for <file>: ...` | The job could not start, for example an execution of the same instance is still `STARTED` | Check `/actuator/ingestjobs`; see [A job stuck in STARTED](#a-job-stuck-in-started) |
| intake | `Processing <file> failed; it will be retried on a later poll` | An error outside the job (download, staging, a remote move) | Fix the cause; the file is retried while it is still in `/inbound` |
| intake | `Business date pinned to 2026-10-15 by remit.clock.fixed-date; use only for demos and tests` (WARN at startup) | `REMIT_CLOCK_FIXED_DATE` is set ([ADR 0005](decisions/0005-demo-clock.md)) | Expected in compose and the kind overlay; must not appear in a real deployment |
| routing | `Chaos hook enabled: remittanceInfo FORCE_FAIL forces a routing failure` (WARN at startup) | `remit.routing.chaos-enabled` is on | Expected in `local`, `docker` and the kind overlay |
| routing | `Resuming an unfinished instruction, attempt N` | A redelivery, retry or replay of an instruction whose ledger row is still `ROUTING` | Normal after a restart, a retry or a replay; repeated attempts for one key end on the DLT |
| routing | `Duplicate delivery of an instruction already ROUTED; not routed again` | A cross-file or redelivered duplicate | None; it appears as `DUPLICATE` in the instruction history |
| routing | `Instruction dead-lettered: <cause>` | Three failed attempts or a non-retryable failure | [Inspect the DLT](#inspect-the-dlt) |
| routing | `Unreadable record <topic>-<p>@<offset> dead-lettered without a status event: ...` | A poison pill on `payments.instructions` | Find the producer; it cannot be replayed |
| api | `Skipping <topic>-<p>@<offset> after it failed: ...` (ERROR) | A record failed five times in a read model listener and was skipped; the read model misses it | Fix the cause (usually MySQL or MongoDB), then rebuild the read model ([Reset the local stack](#reset-the-local-stack)) |
| api | `Username <name> locked after repeated failed logins` | Five failed logins within 10 minutes | [Recover from a login lockout](#recover-from-a-login-lockout) |
| api | `REMIT_JWT_SECRET is not set` or `... HS256 needs at least 32 bytes` (startup fails) | Missing or short signing key | Set it as in [Rotate the JWT secret](#rotate-the-jwt-secret) |
| api | `Unhandled exception` (ERROR; the client got a 500 ProblemDetail) | A bug or an unexpected infrastructure error; the stack trace is in the log only | Find the line by the `X-Correlation-Id` of the response |
| any | `Access denied for user 'remit_...'` (MySQL driver) | The database password in `.env` or the Secret does not match the server | [Rotate other secrets](#rotate-other-secrets), or reset the stack |
| any | `Communications link failure` (MySQL driver), `Connection to node -1 ... could not be established` (Kafka client) | MySQL or Kafka is down or not ready yet | `docker compose ps` or `kubectl -n remit get pods`; the services recover when it is back, or [restart them](#restart-a-service) if their health stays `DOWN` |
| any | Flyway `Validate failed: Migrations have failed validation` | An applied migration was edited | Never edit an applied migration; reset the local stack |
| api | `Command failed with error 18 (AuthenticationFailed)` (MongoDB driver) | `REMIT_MONGO_PASSWORD` does not match the `remit_audit` user | As for the MySQL passwords above |

## Run the CI pipeline and refresh its evidence

The Jenkins pipeline runs on the local CI stack ([ci/README.md](../ci/README.md)).

1. Commit: the job clones `main` from the repository mounted at `/repo`, so uncommitted changes are not built.
2. `ci/setup.sh` starts (or checks) SonarQube and Jenkins. It is safe to rerun: it keeps a valid SonarQube token and does not restart a Jenkins whose image and configuration are unchanged.
3. Open <http://localhost:8090/job/remit/> (admin, `JENKINS_ADMIN_PASSWORD` from `ci/.env`) and click **Build Now**. A failed stage shows its log under the stage; the E2E and API test stages also archive `compose-ps.txt` and `compose-logs.txt`.
4. If the quality gate fails, open <http://localhost:9000/dashboard?id=ca.musakaleem.remit:remit>, fix the reported code, commit, and build again. Do not relax the gate.
5. The screenshots in `docs/img/` come from a green build: the job's **Full Stage View** (`jenkins-pipeline-green.png`), the build's **Tests** page (`jenkins-tests.png`), the SonarQube project overview with **Overall Code** selected (`sonarqube-dashboard.png`) and **Quality Gates > remit** (`sonarqube-quality-gate.png`), each at a 1400 px wide window and under 500 KB.

### Rotate the SonarQube token

Revoke the token `jenkins` of the user admin in SonarQube (**My Account > Security**) and rerun `ci/setup.sh`: it finds the stored token invalid, generates a new one, writes it to `ci/.env` and recreates the Jenkins container so the credential `sonar-token` holds it.
