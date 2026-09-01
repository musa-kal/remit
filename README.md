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
| `routing-service/` | Idempotent Kafka consumer and channel routing |
| `api-service/` | Operator REST API, security, read model, audit writer |
| `docker/` | Init scripts for MySQL, MongoDB and Kafka topics used by compose |
| `docker-compose.yml` | Local infrastructure stack |
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

Prerequisites: Docker with Compose v2 and JDK 17. The application services join the compose stack in a later milestone; today compose runs the infrastructure they need.

```bash
cp .env.example .env        # then replace every change-me value
docker compose up -d        # MySQL, MongoDB, Kafka (+ topics), SFTP
docker compose ps           # four services healthy, kafka-init "Exited (0)"
docker compose --profile tools up -d kafka-ui   # optional Kafka UI on http://localhost:8090
docker compose down -v      # stop everything and delete all data volumes
```

From empty volumes with images already pulled, the stack reached all-healthy in 19 s (measured on Windows 11 with Docker Desktop).

### Local infrastructure

| Service | Host address | What it provides |
|---|---|---|
| `mysql` (8.4) | `localhost:3307` | Schemas `remit_intake`, `remit_routing`, `remit_api`; users of the same names, each granted only on its own schema |
| `mongo` (7.0) | `localhost:27018` | Database `remit_audit` with collection `audit_events` and its indexes; the app user can insert and read but not update or delete |
| `kafka` (3.9, KRaft) | `localhost:29092` (host), `kafka:9092` (compose network) | Single broker, topic auto-creation disabled |
| `kafka-init` | none | Creates the eight `payments.*` topics with 3 partitions, then exits 0 |
| `sftp` (atmoz/sftp) | `localhost:2222` | User `REMIT_SFTP_USER` with `inbound`, `processing`, `processed`, `error` |
| `kafka-ui` (tools profile) | `localhost:8090` | Browse topics and messages |

Notes:

- MySQL and MongoDB publish on 3307 and 27018 so they do not clash with native installs; change `REMIT_MYSQL_PORT` / `REMIT_MONGO_PORT` in `.env` if needed.
- The init scripts in `docker/` run only when a data volume is empty. After changing a password in `.env`, recreate with `docker compose down -v`.
- The SFTP host key is regenerated whenever the container is recreated, so local clients should not pin it.

## Status

- [x] M0 Scaffold: parent POM, four modules, Spotless and JaCoCo configured
- [x] M1 Local infra: MySQL (3 schemas), MongoDB, Kafka and topics, SFTP via Docker Compose
- [ ] M2 Common + intake: event records, masking and idempotency utilities, Spring Batch intake
- [ ] M3 Routing: idempotent consumer, retries, dead-letter topic
- [ ] M4 API + security + audit: REST endpoints, JWT and roles, MongoDB audit trail, DLT replay
- [ ] M5 Containers + E2E: Dockerfiles, full compose stack, smoke test, measured throughput
- [ ] M6 Postman: collection and environments, Newman run
- [ ] M7 CI: Jenkins and SonarQube, quality gate
- [ ] M8 Kubernetes + scripts: kustomize base and local overlay, ops scripts
- [ ] M9 Docs: runbook, architecture notes, ADRs
