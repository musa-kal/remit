# Architecture decision records

Each record states the context, the decision and its consequences. All are accepted and describe the code as it is on `main`. New records take the next number and the same headings (Context, Decision, Consequences).

| ADR | Title | Summary |
|---|---|---|
| [0001](0001-service-boundaries.md) | Service boundaries | Three services plus `remit-common`; each owns its MySQL schema (enforced by per-schema grants) and shares data only through Kafka events. |
| [0002](0002-idempotency.md) | Idempotent routing with a database ledger | `processed_instruction` keyed by idempotency key, two short transactions around an acknowledged publish; the crash table and what "exactly once" does and does not guarantee. |
| [0003](0003-dlt-and-replay.md) | Retries, the dead-letter topic and replay | Blocking `DefaultErrorHandler` with 3 attempts, `payments.instructions.DLT` with Spring's headers, poison pills skip retries; api-service replays with a cap of 3 and the PENDING / REPLAY_REQUESTED / REPLAYED / EXHAUSTED state machine. |
| [0004](0004-jwt-approach.md) | JWT approach for the operator API | jjwt with HS256 and a custom filter instead of `oauth2-resource-server`; 15 minute tokens, `@PreAuthorize` roles, users seeded in Java, in-memory login lockout. |
| [0005](0005-demo-clock.md) | Demo clock and sample value dates | Injected `Clock` and optional `REMIT_CLOCK_FIXED_DATE` so the committed samples stay valid; tests pin 2026-10-15. |
| [0006](0006-sample-duplicate-instruction.md) | Where the sample files show a duplicate instruction | Keys are client scoped, so the cross-file duplicate is `ACME-0003` in an ACME01 file; BETA02 reusing `ACME-0001` is a new instruction. |
| [0007](0007-intake-failure-handling.md) | How intake-service fails a file, a batch or a job | File-level rejections end the job as REJECTED, too many bad rows end the batch FAILED, technical failures leave the job restartable with the file in `/processing`. |
| [0008](0008-channel-rules-per-service.md) | Channel rules are configured per service with the same keys | intake and routing each bind `remit.routing.rules` themselves; routing is the stricter gate. |
| [0009](0009-audit-event-types.md) | Audit event types and ids | The spec's eight types plus `INSTRUCTION_STATUS_CHANGED` and `DEAD_LETTER_RECORDED`; deterministic event ids make audit inserts idempotent. |
| [0010](0010-api-read-model.md) | The api-service read model | Upserts by natural key ordered by `occurredAt`, routed topics as the source of payment details, one dead letter row per failed instruction across its replays. |
| [0011](0011-local-stack-settings.md) | Settings of the local compose stack | Relaxed MySQL fsync, 10 s poll and a pinned business date in compose only, and why. |
| [0012](0012-ci-docker-strategy.md) | Docker access for the Jenkins pipeline | Docker-in-Docker sidecar instead of the host socket, so compose bind mounts and Testcontainers work on Docker Desktop for Windows. |
| [0013](0013-kubernetes-local-overlay.md) | Kubernetes manifests and the local kind overlay | kind, NodePort 30080 mapped to `localhost:8080`, infrastructure as plain StatefulSets, shared init scripts, restricted pod security. |
| [0014](0014-intake-step-components.md) | Components of the intake job and the SFTP poller | Own `ItemProcessor` instead of `ValidatingItemProcessor`, a `KafkaTemplate` writer instead of `KafkaItemWriter` (headers), a scheduled poller over a gateway interface. |
