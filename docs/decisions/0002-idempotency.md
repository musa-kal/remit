# ADR 0002: Idempotent routing with a database ledger

- Status: accepted
- Date: 2026-09-30

## Context

Kafka delivers `payments.instructions` to routing-service at least once. The same instruction can arrive more than once for several reasons:

- intake-service publishes it again after a failed chunk is restarted (ADR 0007), or a client sends the same `instruction_ref` in a later file.
- routing-service crashes, or a consumer rebalance happens, after processing a record but before its offset is committed, so the record is delivered again.
- The error handler retries a record after a failure (ADR 0003), and api-service replays a dead-lettered record.

Each instruction must be routed to its channel topic at most once in effect, and every delivery after the first must be reported as `DUPLICATE`.

The producer settings `acks=all` and `enable.idempotence=true` do not solve this. Producer idempotence only stops the broker from writing the same batch twice when the producer retries a send inside one producer session. It knows nothing about business keys: a second send of the same instruction (a redelivery, a restarted intake chunk, a replay) is a new record with a new sequence number and is written normally.

Kafka transactions with "exactly-once semantics" (read-process-write with `sendOffsetsToTransaction`) do not solve it either. They make "consume, produce, commit offset" atomic inside Kafka, so a crash cannot leave output without an offset commit. They do not cover the MySQL write, they do not help when the same instruction arrives as two different records, and consumers of the output would also have to read with `isolation.level=read_committed`. Exactly-once in Kafka means "exactly once within Kafka", not "exactly once across Kafka, a database and duplicate inputs".

## Decision

routing-service keeps an idempotency ledger in MySQL, `remit_routing.processed_instruction`, whose primary key is the instruction's `idempotency_key` (`SHA-256(clientId + "|" + instruction_ref)`, spec 3.6). The unique constraint is the single source of truth for "has this instruction been seen". `RoutingService` implements FR-RTE-02 with explicit transaction boundaries (`TransactionTemplate`):

1. **Claim (transaction 1).** Insert a row with status `ROUTING`, `attempts = 1`. If the insert commits, this delivery owns the instruction.
2. **Duplicate check.** If the insert fails with `DataIntegrityViolationException`, open a new transaction and load the existing row:
   - `ROUTED` or `REQUIRES_REVIEW`: publish `InstructionStatusEvent(DUPLICATE)` with detail `already <status>`, return (the offset is committed). The row is not changed.
   - `ROUTING`: an earlier attempt failed or crashed mid-way. Increment `attempts` and continue.
   - No row: the insert failed for another reason (for example a value too long). Rethrow.
3. **Route (no transaction).** Apply the FR-RTE-04 chaos hook, re-check the channel rules of spec 3.3 (a violation throws `NonRetryableRoutingException`), and publish the instruction to `payments.routed.<channel>` keyed by `idempotencyKey` with `send(...).get(timeout)`. No database transaction is open while waiting for Kafka.
4. **Record (transaction 2).** Set the row to `ROUTED`, or `REQUIRES_REVIEW` for a WIRE of 1,000,000.00 or more, and commit. `@Version` makes a concurrent update of the same row fail instead of overwriting it.
5. **Announce.** After the commit, publish `REPLAYED` first when `replayCount > 0`, then `ROUTED` or `REQUIRES_REVIEW`, to `payments.status-events` keyed by `idempotencyKey`.
6. **Ack.** The listener returns and the container commits the offset (`AckMode.RECORD`, `enable.auto.commit=false`).

`attempts` counts processing attempts that reached step 2 with a `ROUTING` row: the first insert, every retry, and every replay. Duplicate deliveries of a finished instruction do not change it.

## What happens on a crash

| Crash point | State left behind | Next delivery |
|---|---|---|
| Before step 1 commits | Nothing | Processed as new |
| After step 1, before the routed publish | Row `ROUTING` | Resumed, routed once |
| After the routed publish, before step 4 commits | Row `ROUTING`, routed record on the channel topic | Resumed and **published to the channel topic again** |
| After step 4, before the status event | Row `ROUTED`, no status event | Reported as `DUPLICATE` with detail `already ROUTED`; the `ROUTED` event is never sent |
| After the status event, before the offset commit | Row `ROUTED`, status event sent | Reported as `DUPLICATE` |

The third row is the classic dual-write gap: a Kafka publish and a MySQL commit cannot be atomic without a transactional outbox. It is accepted because routed topics are keyed by `idempotencyKey`, so the repeat lands on the same partition with the same key, and a downstream consumer must deduplicate by that key. Stretch item S4 (transactional outbox) would remove the gap.

The fourth row is why the `DUPLICATE` event carries the existing status in `detail`: api-service can set the instruction's status from it when it never saw the `ROUTED` event.

Ordering keeps the ledger honest: the ledger never says `ROUTED` for an instruction that was not published, because step 4 runs only after the broker acknowledged the routed record.

## Consequences

- Duplicate detection survives restarts, rebalances and scaling out (two replicas in Kubernetes) because it lives in the database, not in memory.
- A given instruction is keyed by debtor account on `payments.instructions`, so its deliveries normally reach the same consumer thread in order. If two consumers ever race on the same key, the primary key lets only one insert win, and `@Version` protects the update.
- The routed topics are at-least-once; downstream consumers deduplicate by key. Status events are at-least-once too; api-service upserts by idempotency key and treats `DUPLICATE` as history, never as a downgrade of `ROUTED` or `REQUIRES_REVIEW`.
- Every instruction costs two short MySQL transactions and two acknowledged Kafka sends. That is slower than batching but keeps the algorithm easy to reason about; NFR-05 measures whether it is fast enough.
