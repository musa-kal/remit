# ADR 0001: Service boundaries

- Status: accepted
- Date: 2026-09-30

## Context

Remit has three distinct jobs: pulling payment files off SFTP and validating them, routing each valid instruction to a payment channel exactly once, and giving operators a secured view of what happened (including replaying failures). These jobs scale differently (intake is bursty and file-bound, routing is per-message and benefits from parallel consumers, the API is request-driven) and fail differently (a bad file must not stop routing; an API outage must not stop ingestion).

A single application would be simpler to run, but it would couple the failure modes and hide the event contracts that make the system auditable and replayable.

## Decision

Split the system into three Spring Boot services plus one shared library:

- `intake-service` owns SFTP polling, file staging, the Spring Batch job, and MySQL schema `remit_intake` (file registry and Spring Batch metadata).
- `routing-service` owns idempotent consumption of `payments.instructions`, the routing rules, the dead-letter handling, and schema `remit_routing`.
- `api-service` owns authentication, the operator REST API, the read model in schema `remit_api`, the MongoDB audit trail, and DLT replay.
- `remit-common` holds only the event records, enums, topic names, and small pure utilities (masking, idempotency keys). It has no Spring beans that talk to infrastructure.

Services share data only through Kafka events. A service never connects to another service's schema: each MySQL user is granted privileges on its own schema only, so the rule is enforced by the database and not just by convention.

## Consequences

- Each service can be deployed, scaled, and restarted on its own (routing-service runs two replicas in Kubernetes).
- The Kafka event records in `remit-common` are the contract between services; changing them is a cross-service change and carries a `schemaVersion` header.
- `api-service` sees state only as fast as events arrive, so its read model is eventually consistent.
- There is more infrastructure to run locally (MySQL, MongoDB, Kafka, SFTP); Docker Compose and the Kubernetes overlay provide it in one command.
- Cross-service queries are not possible by design; anything an operator needs must be carried in an event and projected by `api-service`.
- The topic table (keys, producers, consumers) and the schemas each service owns are listed in [architecture.md](../architecture.md#kafka-topics).
