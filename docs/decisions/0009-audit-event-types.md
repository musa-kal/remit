# ADR 0009: Audit event types and ids

- Status: accepted
- Date: 2026-09-30

## Context

FR-API-02 says every consumed event and every operator action writes one document to `remit_audit.audit_events`, and spec 8.2 lists eight event types: `BATCH_RECEIVED`, `BATCH_COMPLETED`, `INSTRUCTION_ROUTED`, `INSTRUCTION_REJECTED`, `INSTRUCTION_DEAD_LETTERED`, `DLT_REPLAY_REQUESTED`, `LOGIN_SUCCESS`, `LOGIN_FAILURE`.

api-service consumes more kinds of records than those eight names cover: status events carry five statuses, and dead-letter records are a separate topic from the `DEAD_LETTERED` status event. It also reads the three `payments.routed.*` topics (see [ADR 0010](0010-api-read-model.md)).

## Decision

### Types

| Source | Audit `eventType` | `entityType` / `entityId` |
|---|---|---|
| `payments.batch-events`, status RECEIVED | `BATCH_RECEIVED` | BATCH / batch id |
| `payments.batch-events`, status COMPLETED, COMPLETED_WITH_ERRORS or FAILED | `BATCH_COMPLETED` (the final status is in `details.status`, the reason code in `details.reasonCode`) | BATCH / batch id |
| `payments.routed.eft`, `.wire`, `.interac` | `INSTRUCTION_ROUTED` | INSTRUCTION / idempotency key |
| `payments.status-events`, status DEAD_LETTERED | `INSTRUCTION_DEAD_LETTERED` | INSTRUCTION / idempotency key |
| `payments.status-events`, any other status | `INSTRUCTION_STATUS_CHANGED` (new; status in `details.status`) | INSTRUCTION / idempotency key |
| `payments.rejected` | `INSTRUCTION_REJECTED` | BATCH / batch id (a rejected row has no valid key) |
| `payments.instructions.DLT` | `DEAD_LETTER_RECORDED` (new) | INSTRUCTION / idempotency key, or DEAD_LETTER / dead letter id for an unreadable record |
| `POST /dead-letters/{id}/replay` | `DLT_REPLAY_REQUESTED` | INSTRUCTION / idempotency key |
| `POST /auth/login` | `LOGIN_SUCCESS`, `LOGIN_FAILURE` | USER / username |

The spec's eight types keep their meaning. The two additions keep the rule "one document per consumed record" without folding different facts into one type: a ROUTED status event and the routed record itself are different records, and so are the DEAD_LETTERED status event and the DLT record. The `BATCH_*` names mirror intake-service's `eventType` header, which also uses `BATCH_COMPLETED` for every final status.

### Actor

`system:api-service` for consumed records, `user:<username>` for operator actions (the attempted username for a failed login).

### Event ids and idempotency

The unique index on `eventId` turns a second insert of the same event into a no-op (`MongoAuditEventStore` treats the duplicate key as "already written"). So the id must be the same every time a record is consumed:

- Consumed events use their Kafka `eventId` header. A record without a readable one gets a name-based UUID of `topic:partition:offset`.
- A DLT record's original `eventId` header belongs to the instruction, not to the failure, so dead letters use a name-based UUID of `dlt:<original topic>:<original partition>:<original offset>`. The same failure dead-lettered twice is audited once; a replay that fails again is a new failure with a new id.
- Operator actions get a random UUID.

### Details

`details` holds a few facts about the record (status, counts, reason codes, replay count, and `readModelUpdated`, which is false when an older event was ignored). `AuditDetails` masks every value under a key containing "account" to its last four characters and every account-like run of 7 or more digits in other text, and writes amounts and times as strings.

## Consequences

- The audit trail can be filtered by the spec's types without surprises, and the two extra types are easy to exclude.
- Each instruction produces several documents (routed record, status events, possibly dead-letter records and replays), which is the point of an audit trail but grows the collection faster than one document per instruction.
- The application user can only `find` and `insert` (docker/mongo/init.js), and the store interface has no update or delete method, so the trail is append-only in code and in the database.
