# Remit

Remit is a payment file gateway. Clients drop CSV payment batch files on an SFTP server; `intake-service` picks them up, validates every payment instruction with Spring Batch, and publishes valid instructions to Kafka. `routing-service` consumes those instructions idempotently and routes each one to a payment channel (EFT, WIRE, INTERAC), dead-lettering failures after retries. `api-service` gives operators a JWT-secured REST API to monitor batches, inspect rejected rows, and replay dead-lettered messages, and it writes every state change to an append-only audit trail in MongoDB. No real money moves and no real bank is involved: channel limits and rules are illustrative.

## Architecture

```
            +-----------+       SFTP poll        +-------------------+
 client --> | SFTP      | <--------------------- |  intake-service   |
 drops CSV  | /inbound  |  download, move file   |  (Spring Batch)   |
            +-----------+                        +---------+---------+
                                                           |
                       payments.instructions  (valid)      |   payments.rejected (invalid rows)
                       payments.batch-events                v
                                                  +-------------------+
                                                  |      Kafka        |
                                                  +----+---------+----+
                                                       |         ^
                         consume instructions          |         | routed + status events
                                                       v         |
                                                  +-------------------+      +---------+
                                                  |  routing-service  | ---> | MySQL   |
                                                  |  (idempotent)     |      | routing |
                                                  +---------+---------+      +---------+
                                                            | failures after retries
                                                            v
                                                  payments.instructions.DLT
                                                            |
                                                            v
 operator --JWT--> +-------------------+  consumes batch/status/rejected/DLT events
                   |    api-service    | ---> MySQL (api read model)
                   | (Spring Web/Sec)  | ---> MongoDB (audit_events, append-only)
                   +-------------------+  replay: republish DLT payload to payments.instructions
```

Each service owns its own MySQL schema (`remit_intake`, `remit_routing`, `remit_api`) and never reads another service's data; everything crosses service boundaries as a Kafka event. See [ADR 0001](docs/decisions/0001-service-boundaries.md).

## Stack

- Java 17, Spring Boot 3 (3.5.x), Maven multi-module build
- Spring Web, Spring Security (JWT, role-based endpoints)
- Spring Batch for file ingestion
- Spring Data JPA / Hibernate on MySQL 8, Flyway migrations
- Apache Kafka 3 (KRaft) with Spring Kafka
- MongoDB 7 for the audit trail
- SFTP (atmoz/sftp) as the file drop zone
- Docker and Docker Compose, Kubernetes manifests (kustomize)
- Bash ops scripts
- Jenkins pipeline with JUnit, Postman/Newman and SonarQube
- JUnit 5, Mockito, AssertJ, Testcontainers; JaCoCo coverage gate and Spotless (google-java-format)

## Repository layout

| Path | Contents |
|---|---|
| `remit-common/` | Shared event records, enums, topic names, masking utilities |
| `intake-service/` | SFTP polling and the Spring Batch ingestion job |
| `routing-service/` | Idempotent Kafka consumer, channel routing, retries and the dead-letter topic |
| `api-service/` | Operator REST API, security, read model, audit writer |
| `docker/` | Init scripts for MySQL, MongoDB and Kafka topics used by compose |
| `docker-compose.yml` | Local stack: infrastructure and the three services |
| `*/Dockerfile` | Multi-stage image build of each service |
| `samples/` | The five sample batch files (see [Intake](#intake-service)) |
| `scripts/` | Bash ops scripts: build, deploy, restart, seed SFTP, logs, smoke and performance tests |
| `docs/api.md` | Operator API reference: endpoints, auth flow, examples, error format |
| `docs/openapi.json` | OpenAPI document exported by the build |
| `docs/runbook.md` | Operational procedures (restart a failed batch job, inspect and replay the DLT, rotate the JWT secret) |
| `docs/e2e-expectations.md` | Expected outcome of every sample file |
| `docs/decisions/` | Architecture decision records |

## Build

Requires JDK 17. The Maven wrapper downloads Maven 3.9.9 on first use.

```bash
./mvnw -B verify          # compile, unit + integration tests, Spotless check, JaCoCo gate
./mvnw -B spotless:apply  # reformat Java sources with google-java-format
./mvnw -B -Powasp verify  # optional OWASP dependency report (target/dependency-check-report.html)
```

The JaCoCo check fails any module whose line coverage drops below 80%.

## Quick start

Prerequisites: Docker with Compose v2, Bash (Git Bash works on Windows), `curl` and `jq`. JDK 17 is only needed to build and test outside Docker; the images compile the code themselves.

```bash
git clone https://github.com/Musa-Kal/remit.git && cd remit
cp .env.example .env              # then replace every change-me value (openssl rand -base64 48 for the JWT secret)
scripts/deploy.sh compose         # builds missing images, starts everything, waits until all services are healthy
scripts/seed-sftp.sh              # drops the five sample files into SFTP /inbound
scripts/smoke-test.sh --keep      # asserts every sample outcome through the API (see the note below)
```

`smoke-test.sh` needs an empty stack, because the samples are accepted only once (duplicate file detection). Run it instead of `seed-sftp.sh` on a fresh deployment, or with `--reset` to start from empty volumes; without `--keep` it removes the stack when it is done.

Then:

- Swagger UI: <http://localhost:8080/swagger-ui.html> (log in with `POST /api/v1/auth/login` as `operator` or `admin` and the passwords from `.env`, then use **Authorize** with the token)
- Kafka UI: `docker compose --profile tools up -d kafka-ui`, then <http://localhost:8090>
- Logs: `scripts/logs.sh intake-service` (JSON, pretty-printed with jq)
- Stop: `docker compose down` (keeps data) or `docker compose down -v` (deletes all volumes)

With image layers already built, `docker compose down -v` followed by `docker compose up -d --build` reached all-healthy in @@UP_WARM@@ (Windows 11, Docker Desktop). The first build of the three images downloads the Maven dependencies and takes about 2 minutes per image.

### Services and ports

| Service | Host address | What it provides |
|---|---|---|
| `api-service` | `localhost:8080` | Operator REST API, Swagger UI, actuator health |
| `intake-service` | `localhost:8081` | Actuator health and `/actuator/ingestjobs` (job executions and restarts, see the runbook) |
| `routing-service` | `localhost:8082` | Actuator health |
| `mysql` (8.4) | `localhost:3307` | Schemas `remit_intake`, `remit_routing`, `remit_api`; users of the same names, each granted only on its own schema |
| `mongo` (7.0) | `localhost:27018` | Database `remit_audit` with collection `audit_events` and its indexes; the app user can insert and read but not update or delete |
| `kafka` (3.9, KRaft) | `localhost:29092` (host), `kafka:9092` (compose network) | Single broker, topic auto-creation disabled |
| `kafka-init` | none | Creates the eight `payments.*` topics with 3 partitions, then exits 0 |
| `sftp` (atmoz/sftp) | `localhost:2222` | User `REMIT_SFTP_USER` with `inbound`, `processing`, `processed`, `error` |
| `kafka-ui` (tools profile) | `localhost:8090` | Browse topics and messages |

Notes:

- The three services run the `docker` profile and wait for healthy MySQL, MongoDB and SFTP and for `kafka-init` to finish. They get only the variables they need from `.env`; the `REMIT_*_HOST` values in `.env` are for services run outside compose.
- Compose pins the business date to 2026-10-15 (`REMIT_CLOCK_FIXED_DATE`, [ADR 0005](docs/decisions/0005-demo-clock.md)) so the committed samples always validate, polls SFTP every 10 s (`REMIT_SFTP_POLL_INTERVAL`), and turns on the `FORCE_FAIL` chaos hook.
- MySQL runs with `innodb_flush_log_at_trx_commit=2` and `sync_binlog=0`: a crash can lose the last second of commits, a fair trade for a local stack where per-commit fsyncs made routing about four times slower (see [Measured performance](#measured-performance)).
- MySQL and MongoDB publish on 3307 and 27018 so they do not clash with native installs; change `REMIT_MYSQL_PORT` / `REMIT_MONGO_PORT` in `.env` if needed.
- The init scripts in `docker/` run only when a data volume is empty. After changing a password in `.env`, recreate with `docker compose down -v`.
- The SFTP host key is regenerated whenever the container is recreated, so local clients should not pin it.

### Container images

Each service has a two-stage `Dockerfile` built from the repository root (it needs the parent pom and `remit-common`):

1. `maven:3-eclipse-temurin-17` copies the poms first and resolves dependencies in their own layer, so a code change does not download anything again, then packages the service with `-DskipTests` (tests run in `./mvnw verify` and CI, not in the image build).
2. `eclipse-temurin:17-jre` receives the Spring Boot layers (`java -Djarmode=tools -jar app.jar extract --layers`, the Boot 3.3+ successor of `layertools`) as four `COPY` layers: dependencies, loader, snapshot dependencies, application. It runs as uid 10001 with `-XX:MaxRAMPercentage=75`, and `HEALTHCHECK` curls `/actuator/health`. Extra JVM flags go in `JAVA_TOOL_OPTIONS`.

Images are named `remit/<service>:<tag>`; compose uses `REMIT_IMAGE_TAG` (default `latest`) and `scripts/build.sh` tags both the git short SHA and `latest`.

### Scripts

All scripts take `-h` for help, use `set -euo pipefail`, read `.env`, and pass `shellcheck scripts/*.sh`.

| Script | What it does |
|---|---|
| `scripts/build.sh [--skip-tests]` | `./mvnw -B verify`, then builds the three images tagged with the git short SHA and `latest` |
| `scripts/deploy.sh compose\|k8s` | `docker compose up -d` (`--build` to rebuild) and waits up to 180 s for every service to be healthy, printing each status; `k8s` runs `scripts/k8s-up.sh` (Kubernetes milestone) |
| `scripts/restart.sh <service> [compose\|k8s]` | Restarts one service and waits until it is healthy again (`kubectl rollout restart` and `rollout status` for k8s) |
| `scripts/seed-sftp.sh [file...]` | Uploads files (default: `samples/*.csv`) to `/inbound`. Default transport streams them into the compose `sftp` container (needs only the docker CLI); `--via sftp` uses `sftp` and `sshpass` like a real client |
| `scripts/logs.sh <service>` | Follows a service's JSON logs, pretty-printed with jq (`-c` compact, `--no-follow`, `-n LINES`) |
| `scripts/gen-batch.sh <clientId> <rows>` | Generates a valid batch file; `-r FORCE_FAIL` makes every row fail in routing |
| `scripts/smoke-test.sh` | End-to-end test of the samples and a dead-letter replay ([Sample walkthrough](#sample-walkthrough)) |
| `scripts/perf-test.sh` | NFR-05 measurement with a 10,000-row file ([Measured performance](#measured-performance)) |

Every file lands in `/inbound` under a hidden `.part` name and is renamed once complete, so the poller never reads a partial upload.

## Sample walkthrough

After `scripts/deploy.sh compose` and `scripts/seed-sftp.sh`, intake-service picks the five files up on its next poll (within 10 s) in file name order, and the API has settled a few seconds later. `scripts/smoke-test.sh` checks all of the following automatically (70 checks); to look yourself:

```bash
set -a; . ./.env; set +a
TOKEN=$(curl -s -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"operator\",\"password\":\"$REMIT_OPERATOR_PASSWORD\"}" | jq -r .accessToken)
curl -s localhost:8080/api/v1/stats/summary -H "Authorization: Bearer $TOKEN" | jq
curl -s "localhost:8080/api/v1/batches?clientId=ACME01" -H "Authorization: Bearer $TOKEN" | jq '.content[] | {fileName, status, totalRows, acceptedRows, rejectedRows, failureReason}'
```

| File | What it exercises | What the API shows |
|---|---|---|
| `PAY_ACME01_20261015093000.csv` | The happy path: 10 valid rows over EFT, WIRE and INTERAC | Batch `COMPLETED`, 10/10 accepted, 10 instructions `ROUTED`; the file moves to `/processed` |
| `PAY_ACME01_20261015100000.csv` | Row-level validation: 20 rows, one invalid account, amount, currency, value date and INTERAC limit; one row repeats `ACME-0003` from the first file | Batch `COMPLETED_WITH_ERRORS`, 15 accepted, 5 rejected; `/batches/{id}/rejections` lists lines 4, 7, 10, 14, 17 with their reason codes and masked accounts; 14 instructions `ROUTED`, because routing-service recognises `ACME-0003` as a duplicate (`duplicateDeliveries: 1`, and its history on `/instructions/{key}` ends with `DUPLICATE`, detail `already ROUTED`) |
| `PAY_ACME01_20261015110000.csv` | Duplicate file: byte-identical to the first file | Batch `FAILED` with `failureReason: DUPLICATE_FILE` and no rows; the file moves to `/error` next to a `.error.txt` note |
| `PAY_BETA02_20261015120000.csv` | File-level validation: the trailer's control total is 100.00 too high | Batch `FAILED` with `failureReason: CONTROL_TOTAL_MISMATCH`, 4 rows read, none published; `/error` |
| `PAY_BETA02_20261015130000.csv` | Review path and client-scoped keys: two WIREs of 1,000,000.00 or more; reuses the reference `ACME-0001`, which is a new instruction for another client | Batch `COMPLETED`, 4 `ROUTED` and 2 `REQUIRES_REVIEW` |

Totals (`GET /api/v1/stats/summary`): batches 2 `COMPLETED`, 1 `COMPLETED_WITH_ERRORS`, 2 `FAILED`; instructions 28 `ROUTED`, 2 `REQUIRES_REVIEW`; `rejectedRows` 5; `duplicateDeliveries` 1; no dead letters. Every batch event, rejection and status change is also in the audit trail (`GET /api/v1/audit-events`, admin). The full table, including the Kafka side, is [docs/e2e-expectations.md](docs/e2e-expectations.md).

To see a dead letter and a replay, drop a file whose remittance info trips the chaos hook:

```bash
scripts/seed-sftp.sh "$(REMIT_CLOCK_FIXED_DATE=2026-10-15 scripts/gen-batch.sh -o /tmp -r FORCE_FAIL CHAOS01 1)"
curl -s "localhost:8080/api/v1/dead-letters?status=PENDING" -H "Authorization: Bearer $TOKEN" | jq '.content[] | {id, status, replayCount}'
```

After three attempts (about 3 s) the instruction is `DEAD_LETTERED` and a `PENDING` dead letter appears. A replay by `admin` (`POST /api/v1/dead-letters/{id}/replay`) answers 202, a second one while it is pending 409; the replay fails again because the hook is still on, the dead letter returns to `PENDING` with `replayCount` 1, and after the third replay it is `EXHAUSTED`. See [Replay a DLT message](docs/runbook.md#replay-a-dlt-message).

## intake-service

intake-service polls the SFTP `/inbound` directory every `REMIT_SFTP_POLL_INTERVAL` (default 30 s). Each `.csv` file is downloaded to a local staging directory, moved to `/processing`, and run through the Spring Batch job `ingestPaymentFileJob`:

1. `fileValidationStep` checks the file name, rejects a file whose SHA-256 is already in `file_registry`, then checks header, trailer, zero rows, record count and control total. An accepted file gets a batch id and a `RECEIVED` batch event.
2. `instructionStep` reads the rows in chunks of 100, validates every field and channel rule, publishes valid instructions to `payments.instructions` (key: debtor account) and each invalid row to `payments.rejected` with its reason codes and a masked copy of the line.
3. `finalizeStep` publishes the final batch event: `COMPLETED`, `COMPLETED_WITH_ERRORS`, or `FAILED` when more than 50% of the rows, or more than `remit.intake.skip-limit` (1000) rows, were rejected.

Afterwards the remote file moves to `/processed`, or to `/error` next to a `<file>.error.txt` whose first line is the reason code. A job that fails for a technical reason (Kafka or MySQL down) leaves the file in `/processing` and can be restarted from its last committed chunk; see [the runbook](docs/runbook.md). [ADR 0007](docs/decisions/0007-intake-failure-handling.md) explains the three kinds of failure.

Every event carries the headers `eventId`, `eventType`, `occurredAt`, `correlationId` (the batch id) and `schemaVersion`. Logs are JSON with `service`, `correlationId` and `fileName`; account numbers only ever appear masked to their last four digits.

### File format

File name `PAY_<CLIENTID>_<yyyyMMddHHmmss>.csv`, `CLIENTID` being 3 to 10 upper-case letters or digits. UTF-8, comma separated with RFC 4180 quoting, a fixed header line and a trailer `TRAILER,<record_count>,<control_total>`:

```
instruction_ref,debtor_name,debtor_institution,debtor_transit,debtor_account,creditor_name,creditor_institution,creditor_transit,creditor_account,amount,currency,channel,value_date,remittance_info
ACME-0001,Acme Widgets Ltd,001,00011,1000001,Northwind Supplies,002,00022,2000001,1250.00,CAD,EFT,2026-10-15,Invoice 1001
TRAILER,1,1250.00
```

| Field | Rule |
|---|---|
| `instruction_ref` | 1 to 35 of `[A-Za-z0-9-]`, unique within the file |
| names | 1 to 70 characters |
| institution / transit / account | 3 digits / 5 digits / 7 to 12 digits |
| `amount` | greater than 0, at most 2 decimals, at most 999,999,999.99 |
| `currency` / `channel` | `CAD` or `USD` / `EFT`, `WIRE` or `INTERAC` |
| `value_date` | `yyyy-MM-dd`, from today (America/Toronto) to 30 days ahead |
| `remittance_info` | optional, at most 140 characters |

Channel rules, configured under `remit.routing.rules`: INTERAC is CAD only and at most 10,000.00; EFT is at most 25,000,000.00; WIRE has no maximum, and routing-service sends amounts of 1,000,000.00 or more to review (`REQUIRES_REVIEW`). **These limits are illustrative, not the limits of any real payment scheme.**

The instruction idempotency key is `SHA-256(clientId + "|" + instruction_ref)`, so the same reference from the same client is a duplicate in any file, while two clients may use the same reference.

### Sample files

| File | Content | Expected result |
|---|---|---|
| `PAY_ACME01_20261015093000.csv` | 10 valid rows over EFT, WIRE and INTERAC | COMPLETED, 10 published |
| `PAY_ACME01_20261015100000.csv` | 20 rows, 5 invalid (account, amount, currency, value date, INTERAC limit) | COMPLETED_WITH_ERRORS, 15 published, 5 rejected |
| `PAY_ACME01_20261015110000.csv` | byte-identical copy of the first file | FAILED, `DUPLICATE_FILE` |
| `PAY_BETA02_20261015120000.csv` | trailer control total 100.00 too high | FAILED, `CONTROL_TOTAL_MISMATCH` |
| `PAY_BETA02_20261015130000.csv` | two WIREs of 1,000,000.00 or more, one reference reused from ACME01 | COMPLETED, 6 published |

Row-by-row expectations, including what routing-service should do with each instruction, are in [docs/e2e-expectations.md](docs/e2e-expectations.md). The valid rows use value date 2026-10-15, so with the real clock the samples are accepted from 2026-09-15 to 2026-10-15. On any other date set `REMIT_CLOCK_FIXED_DATE=2026-10-15`, which pins the business date for a demo ([ADR 0005](docs/decisions/0005-demo-clock.md)); the integration tests always run with that date.

### Run it locally

With the compose infrastructure up and `.env` filled in:

```bash
./mvnw -B -q package -pl intake-service -am -DskipTests
set -a; . ./.env; set +a
java -jar intake-service/target/intake-service-0.1.0.jar --spring.profiles.active=local

docker compose cp samples/PAY_ACME01_20261015093000.csv sftp:/home/remit/inbound/
curl -s localhost:8081/actuator/health/readiness
curl -s localhost:8081/actuator/ingestjobs      # recent job executions
```

### Generate a large file

```bash
scripts/gen-batch.sh -o /tmp ACME01 10000     # prints /tmp/PAY_ACME01_<timestamp>.csv
VALUE_DATE=2026-10-15 scripts/gen-batch.sh LOAD01 500
scripts/gen-batch.sh -r FORCE_FAIL CHAOS01 1  # every row fails in routing-service (chaos hook)
```

The script writes a valid file with rows spread over the three channels within their limits and a control total summed in integer cents. The value date is `VALUE_DATE` if set, else `REMIT_CLOCK_FIXED_DATE` if set (compose pins 2026-10-15, where tomorrow's real date would be in the past), else tomorrow in America/Toronto. `-r TEXT` sets the remittance info of every row. Run `scripts/gen-batch.sh -h` for all options. It needs GNU or BSD `date` with time zone data.

## routing-service

routing-service consumes `payments.instructions` and routes each instruction to `payments.routed.eft`, `payments.routed.wire` or `payments.routed.interac`, exactly once in effect however often it is delivered.

### Consumer settings

| Setting | Value |
|---|---|
| Consumer group | `routing-service` |
| Offset commits | `enable.auto.commit=false`, `AckMode.RECORD`: the container commits each record's offset after the listener returns |
| Concurrency | 3 consumer threads (one per partition locally) |
| Deserializer | `ErrorHandlingDeserializer` around `JsonDeserializer<PaymentInstruction>`, type headers trusted only from `ca.musakaleem.remit.common.event` |
| Producer | `acks=all`, `enable.idempotence=true`, JSON values, every send awaited (`send(...).get(timeout)`) |

### Algorithm (FR-RTE-02)

1. Insert a `processed_instruction` row with status `ROUTING` in its own transaction. The primary key is the idempotency key.
2. If the key already exists: a `ROUTED` or `REQUIRES_REVIEW` row means this is a duplicate, so publish a `DUPLICATE` status event and stop. A `ROUTING` row means an earlier attempt failed or crashed, so count the attempt and carry on.
3. Re-check the channel rules (a violation is not retried) and publish the instruction to its channel topic, keyed by idempotency key.
4. Set the row to `ROUTED`, or `REQUIRES_REVIEW` for a WIRE of 1,000,000.00 or more (`remit.routing.review-threshold`), and commit. Then publish the status event (`REPLAYED` first for a replayed instruction) to `payments.status-events`, and let the offset be committed.

[ADR 0002](docs/decisions/0002-idempotency.md) explains why this uses a database unique key rather than Kafka's producer idempotence or transactions, and what happens on a crash at each step.

Every routed record and status event carries the five standard headers; routed records also carry `routingStatus` (`ROUTED` or `REQUIRES_REVIEW`). Logs are JSON with `service`, `correlationId` and `idempotencyKey`; the debtor account appears only masked.

### Retries and the dead-letter topic

A failing record is retried with exponential back-off (1 s, then 2 s: three attempts in total) and then published to `payments.instructions.DLT` on the same partition, with its original key, value and headers plus Spring's `kafka_dlt-*` exception and origin headers. A `DEAD_LETTERED` status event with the failure reason follows. A channel rule violation (`NonRetryableRoutingException`) and a record that cannot be deserialized (a poison pill) skip the retries, and a poison pill never blocks the records behind it. api-service replays dead letters by republishing them with `replayCount + 1`, at most three times. See [ADR 0003](docs/decisions/0003-dlt-and-replay.md) and [Inspect the DLT](docs/runbook.md#inspect-the-dlt).

### Chaos hook (FR-RTE-04)

With `remit.routing.chaos-enabled=true` an instruction whose `remittance_info` is exactly `FORCE_FAIL` fails every attempt with a retryable error, so it lands on the DLT after three attempts. It is on in the `local` and `docker` profiles for demos and off by default and in `k8s`.

### Run it locally

With the compose infrastructure up and `.env` filled in:

```bash
./mvnw -B -q package -pl routing-service -am -DskipTests
set -a; . ./.env; set +a
java -jar routing-service/target/routing-service-0.1.0.jar --spring.profiles.active=local

curl -s localhost:8082/actuator/health    # db and kafka components, both UP
```

Run intake-service next to it and drop a sample file (see above); `docs/e2e-expectations.md` lists the routing outcome of every sample instruction.

## api-service

api-service gives operators a JWT-secured REST API over a read model it builds from Kafka, writes an append-only audit trail to MongoDB, and replays dead letters. The full reference, with curl examples, is [docs/api.md](docs/api.md); Swagger UI runs at `http://localhost:8080/swagger-ui.html`.

### Endpoints (`/api/v1`)

| Method | Path | Role |
|---|---|---|
| POST | `/auth/login` | public |
| GET | `/batches`, `/batches/{batchId}`, `/batches/{batchId}/instructions`, `/batches/{batchId}/rejections` | OPERATOR |
| GET | `/instructions/{idempotencyKey}` | OPERATOR (full account numbers for ADMIN only) |
| GET | `/dead-letters` | OPERATOR |
| POST | `/dead-letters/{id}/replay` | ADMIN |
| GET | `/audit-events` | ADMIN |
| GET | `/stats/summary` | OPERATOR |

Lists are paged (`page`, `size` up to 100, larger sizes are clamped to 100, `sort`) and return `{content, page, size, totalElements, totalPages}`. Every error is an RFC 7807 ProblemDetail without stack traces: 400 with field errors, 401 without a valid token, 403 for the wrong role, 404, 409 for a refused replay, 429 for a locked login.

### Security

- `POST /auth/login` returns an HS256 JWT (jjwt) valid for 15 minutes with claims `sub`, `roles`, `iat`, `exp`, `jti`. The key is `REMIT_JWT_SECRET`; api-service does not start without one of at least 32 bytes.
- Stateless filter chain; roles are checked per endpoint with `@PreAuthorize`. Users live in MySQL with BCrypt hashes.
- In the `local` and `docker` profiles api-service creates `operator` (OPERATOR) and `admin` (OPERATOR, ADMIN) at startup from `REMIT_OPERATOR_PASSWORD` and `REMIT_ADMIN_PASSWORD` if they do not exist.
- Five failed logins for a username within 10 minutes lock it for 10 minutes (in memory, per instance).
- Account numbers are masked to the last four digits in every response and audit document, except the instruction detail for ADMIN.

[ADR 0004](docs/decisions/0004-jwt-approach.md) explains the choices.

### Read model

Consumer group `api-service` reads `payments.batch-events`, `payments.status-events`, `payments.rejected`, `payments.instructions.DLT` (as raw bytes, since a poison pill is not JSON) and the three `payments.routed.*` topics (for the payment details of routed instructions) into schema `remit_api`. Upserts are keyed by natural key and ignore events that happened strictly before the stored state, so redelivery and reordering are harmless; a duplicate delivery never downgrades an instruction but is kept in its status history. Dead letters are deduplicated by original topic, partition and offset. See [ADR 0010](docs/decisions/0010-api-read-model.md).

### Audit trail

Every consumed record and every operator action (login, replay) writes one document to `remit_audit.audit_events`, with actor `system:api-service` or `user:<username>`. The unique index on `eventId` makes a redelivered event a no-op; the code only ever inserts (`MongoTemplate.insert`), and the compose MongoDB user may only find and insert. Types and ids: [ADR 0009](docs/decisions/0009-audit-event-types.md).

### Replay

`POST /api/v1/dead-letters/{id}/replay` (ADMIN) republishes the stored instruction to `payments.instructions` with `replayCount + 1`, keyed by debtor account with the five standard headers, marks the dead letter `REPLAY_REQUESTED` and audits `DLT_REPLAY_REQUESTED` with the administrator's name. routing-service's status events then mark it `REPLAYED`, or `PENDING` again if it failed. A replay is refused with 409 while one is pending and once a dead letter has been replayed 3 times (it becomes `EXHAUSTED`). Walkthrough: [Replay a DLT message](docs/runbook.md#replay-a-dlt-message).

### Run it locally

With the compose infrastructure up and `.env` filled in:

```bash
./mvnw -B -q package -pl api-service -am -DskipTests
set -a; . ./.env; set +a
java -jar api-service/target/api-service-0.1.0.jar --spring.profiles.active=local

curl -s localhost:8080/actuator/health    # db, mongo and kafka components, all UP
TOKEN=$(curl -s -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"operator\",\"password\":\"$REMIT_OPERATOR_PASSWORD\"}" | jq -r .accessToken)
curl -s localhost:8080/api/v1/stats/summary -H "Authorization: Bearer $TOKEN"
```

Run intake-service and routing-service next to it and drop the samples; [docs/e2e-expectations.md](docs/e2e-expectations.md) lists what the API should then report. The build (`./mvnw -B verify`) regenerates [docs/openapi.json](docs/openapi.json) from the running application.

## Measured performance

NFR-05 asks that a 10,000-row valid file goes from the SFTP drop to all instructions `ROUTED` in under 60 s on a local compose stack. Measured with `scripts/perf-test.sh --runs 3`: it generates a 10,000-row file with `gen-batch.sh`, takes the start time right before uploading it to `/inbound`, and stops when `GET /api/v1/batches/{id}` reports `ROUTED + REQUIRES_REVIEW = 10000` (polled every 0.5 s). The figure therefore includes the wait for the next SFTP poll, the intake job, routing, and api-service catching up with the status events.

Machine: Windows 11, Docker Desktop (WSL 2), 20 CPUs, 16 GB RAM (8 GB for the Docker VM); every container of the stack on the same machine.

| Series (final configuration) | Run 1 | Run 2 | Run 3 |
|---|---|---|---|
| Right after `docker compose up` (cold JVMs) | 48.7 s | 35.6 s | 24.5 s |
| Directly after the first series | 30.5 s | 34.5 s | 28.6 s |

**Median of the six runs: 32.5 s.** Where the time goes: about 1.3 s to upload, 3 to 12 s until the next poll picks the file up, then 20 to 37 s until the API reports all 10,000 routed (slowest for the first file after startup, while the JVMs warm up). Of that last part the intake job needs about 3.2 s to validate and publish the rows; the rest is routing-service and api-service working through them.

Tuning, starting from the first measurement (one run each unless noted):

| Change | Upload to all routed | Observation |
|---|---|---|
| Baseline: service defaults, SFTP poll every 30 s | 173.9 s | 17.3 s poll wait, then 156.6 s to route: about 100 instructions/s, limited by two fsynced MySQL commits per instruction on Docker Desktop |
| MySQL `innodb_flush_log_at_trx_commit=2`, `sync_binlog=0` (compose only) | 75.9 s | 31.1 s poll wait, 44.7 s to route; routing-service itself took 25.8 s and the API read model finished about 19 s later |
| api-service listener concurrency 1 to 3 | 38.0 s, 65.8 s | poll waits of 8.5 s and 32.1 s; the read model now keeps pace with routing (about 30 s for both) |
| SFTP poll interval 30 s to 10 s (compose only) | median 32.5 s (six runs above) | the poll wait no longer decides whether a run meets the target |

Also tried without a measurable gain: turning off the MySQL binary log. Deliberately unchanged: routing-service still awaits every publish and commits twice per instruction ([ADR 0002](docs/decisions/0002-idempotency.md)), topics keep 3 partitions (so 3 routing threads), and the intake chunk size stays 100.

## Status

- [x] M0 Scaffold: parent POM, four modules, Spotless and JaCoCo configured
- [x] M1 Local infra: MySQL (3 schemas), MongoDB, Kafka and topics, SFTP via Docker Compose
- [x] M2 Common + intake: event records, masking and idempotency utilities, Spring Batch intake
- [x] M3 Routing: idempotent consumer, retries, dead-letter topic
- [x] M4 API + security + audit: REST endpoints, JWT and roles, MongoDB audit trail, DLT replay
- [x] M5 Containers + E2E: Dockerfiles, full compose stack, smoke test, measured throughput
- [ ] M6 Postman: collection and environments, Newman run
- [ ] M7 CI: Jenkins and SonarQube, quality gate
- [ ] M8 Kubernetes + scripts: kustomize base and local overlay, ops scripts
- [ ] M9 Docs: runbook, architecture notes, ADRs
