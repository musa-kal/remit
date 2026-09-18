# ADR 0010: The api-service read model

- Status: accepted
- Date: 2026-09-30

## Context

api-service owns schema `remit_api` and learns about batches and instructions only from Kafka (ADR 0001). FR-API-01 asks for idempotent upserts by natural key that ignore events older than the stored `updated_at`. Events arrive at least once, from several topics, and the listeners of different topics run on different threads.

`GET /instructions/{key}` must show the instruction with full account numbers to an ADMIN (FR-SEC-05), but status events carry only key, batch, status, channel and detail.

## Decision

### Sources

| Table | Written from | Natural key |
|---|---|---|
| `payment_batch` | `payments.batch-events` | batch id |
| `payment_instruction` | status: `payments.status-events` and DLT records; payment details: `payments.routed.*` and DLT records | idempotency key |
| `instruction_status_history` | `payments.status-events` | `eventId` header |
| `instruction_rejection` | `payments.rejected` | batch id + line number |
| `dead_letter` | `payments.instructions.DLT`, then status events | original topic + partition + offset |

The payment details of a routed instruction come from the `payments.routed.*` topics, which spec section 5 lists as "no consumer required". They carry the full `PaymentInstruction`, keyed by idempotency key and written only by routing-service. Consuming `payments.instructions` instead would compete with routing-service's reason for existing and would show instructions that were never routed. Instructions that never get routed (dead letters) get their details from the DLT payload.

### Ordering

`updated_at` holds the `occurredAt` of the last applied event, not the time api-service processed it. An event whose `occurredAt` is strictly before it is ignored for the current state; an equal time is applied, because routing-service publishes `REPLAYED` and then `ROUTED` within the same millisecond and redelivery repeats the same time. Further rules:

- A batch never goes back from a final status to RECEIVED.
- `DUPLICATE` is history only: a second delivery never downgrades a ROUTED instruction. The duplicate's status event names the new batch, which the history keeps.
- Every status event is kept in `instruction_status_history` (once per `eventId`), including ignored ones, so the history shows what arrived.
- Times are truncated to milliseconds before they are stored or compared. MySQL `TIMESTAMP(3)` rounds extra digits, which would otherwise make a later event in the same millisecond look older.

### Concurrent creation

Three listeners can see a new idempotency key at the same moment. Instruction rows are created with `INSERT ... ON DUPLICATE KEY UPDATE` followed by `SELECT ... FOR UPDATE`, so the second listener waits for the first instead of failing on the primary key and being retried. Batches and rejections are keyed by batch id on their topics, so one partition (one thread) sees all events of a batch.

### Dead letters

One `dead_letter` row follows one dead-lettered instruction through its replays:

- A DLT record whose original topic, partition and offset is already stored is a duplicate and changes nothing (routing-service can dead-letter the same record twice).
- A readable DLT record of an instruction that already has an open dead letter (PENDING, REPLAY_REQUESTED or EXHAUSTED) is that dead letter failing again, usually its replay. It updates the row (exception, payload, replay count) instead of adding a second one, unless it carries an older replay count.
- Any other DLT record creates a row, PENDING, or EXHAUSTED if its instruction was already replayed three times. A poison pill has no key; its payload is stored as `{"unreadable": "<masked text>", "sizeBytes": n}`.

The replay lifecycle is driven by status events that happened after the replay was requested: `DEAD_LETTERED` moves REPLAY_REQUESTED back to PENDING, or to EXHAUSTED once `replay_count` is 3; `REPLAYED`, `ROUTED` or `REQUIRES_REVIEW` mark it REPLAYED. The replay endpoint locks the row, refuses (409) a pending, successful, unreadable or capped dead letter, marks a capped one EXHAUSTED, publishes, and only then commits; if Kafka does not acknowledge, nothing changes and the caller gets 503.

### Failures while consuming

Each listener retries a failing record with exponential back-off (0.5 s doubling to 5 s, five attempts), then logs and skips it. Records that cannot be deserialized or have an unexpected type are skipped at once. Every projection is idempotent, so a retry after a partial failure (for example the audit insert failed after the database commit) repeats nothing.

### Paging

List endpoints take Spring's `page`, `size` and `sort`. A `size` above 100 is clamped to 100 (`spring.data.web.pageable.max-page-size`); the response's `size` shows the size used. Sort properties are whitelisted per endpoint; any other is a 400 with a field error.

## Consequences

- The read model is eventually consistent and can be rebuilt by resetting the `api-service` consumer group to the earliest offsets (topics keep their data locally).
- A batch's instruction list shows each instruction under the batch that first carried it; a cross-file duplicate appears in the history of the original instruction, with the new batch id.
- A record skipped after its retries is lost to the read model until the group is reset; the error log names its topic, partition and offset.
