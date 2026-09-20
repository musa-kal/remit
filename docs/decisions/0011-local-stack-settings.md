# ADR 0011: Settings of the local compose stack

- Status: accepted
- Date: 2026-09-30

## Context

`docker-compose.yml` runs the whole system on one laptop. Three things the spec does not settle made the first NFR-05 measurement (10,000 rows from SFTP upload to all routed, target 60 s) take 173.9 s, and made the committed samples depend on the calendar:

- On Docker Desktop every fsync is slow. MySQL's defaults (`innodb_flush_log_at_trx_commit=1`, `sync_binlog=1`) fsync on every commit, and routing-service commits twice per instruction (ADR 0002), so routing ran at about 100 instructions a second.
- The poller's 30 s default (FR-INT-01) left a dropped file waiting up to 32 s before intake-service saw it; that wait alone decided whether a run met the target.
- The samples carry value date 2026-10-15 and are valid against the real clock only until that day (ADR 0005).

## Decision

Only compose changes; the service defaults and the `k8s` profile stay as they are.

- MySQL runs with `--innodb-flush-log-at-trx-commit=2 --sync-binlog=0`. Commits reach the OS at once and the disk about once a second.
- intake-service polls every 10 s (`REMIT_SFTP_POLL_INTERVAL`, default 10s in compose and `.env.example`).
- `REMIT_CLOCK_FIXED_DATE` defaults to 2026-10-15 in compose; an empty value in `.env` switches back to the real date.
- The images are extracted with `java -Djarmode=tools -jar app.jar extract --layers`, the Spring Boot 3.3+ replacement of the deprecated `layertools` mode; the layers are the same.

api-service's listener concurrency went from 1 to 3 as well, but in `application.yml` for every environment, because it matches the three partitions and routing-service's setting (it is not a local-only trade-off).

## Consequences

- If the MySQL container or the Docker VM crashes (a clean stop flushes everything), up to about a second of acknowledged commits can be lost, while the matching Kafka offsets stay committed. A ledger row could then be missing or stay `ROUTING` although its instruction was routed, and a read model row could miss its last update. For a demo stack this is acceptable; a real deployment keeps the durable defaults, which is why the setting lives in compose only.
- The measured NFR-05 median is 32.5 s (README, "Measured performance"), with the tuning steps and their numbers recorded there.
- The poll interval, the business date and the MySQL flags are visible in `docker-compose.yml` and can be changed through `.env`, so nothing about them is hidden from a reviewer.
