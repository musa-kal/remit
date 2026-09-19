# End-to-end expectations for the sample files

`scripts/smoke-test.sh` (M5) uploads every file in `samples/` and compares what the API reports with this table. The intake columns are verified by `SampleFilesJobIT`. The routing columns follow from the routing rules that `RoutingRulesTest` and the routing-service integration tests cover; `smoke-test.sh` checks them end to end.

Assumptions:

- The business date is between 2026-09-15 and 2026-10-15, or `REMIT_CLOCK_FIXED_DATE=2026-10-15` is set (see [ADR 0005](decisions/0005-demo-clock.md)).
- The files are uploaded together on an empty stack. The poller processes them in file name order, so `...093000` is registered before its duplicate `...110000`.
- Channel limits are the illustrative defaults in `intake-service/src/main/resources/application.yml`.

## Batch outcomes (intake-service)

| File | Batch status | Total rows | Accepted | Rejected | Reason codes | Batch events | SFTP destination |
|---|---|---|---|---|---|---|---|
| `PAY_ACME01_20261015093000.csv` | COMPLETED | 10 | 10 | 0 | none | RECEIVED, COMPLETED | `/processed` |
| `PAY_ACME01_20261015100000.csv` | COMPLETED_WITH_ERRORS | 20 | 15 | 5 | one row each: INVALID_ACCOUNT, INVALID_AMOUNT, INVALID_CURRENCY, INVALID_VALUE_DATE, CHANNEL_LIMIT_EXCEEDED | RECEIVED, COMPLETED_WITH_ERRORS | `/processed` |
| `PAY_ACME01_20261015110000.csv` | FAILED | 0 | 0 | 0 | DUPLICATE_FILE (file level) | FAILED only, new batch id, header `reasonCode=DUPLICATE_FILE` | `/error` + `.error.txt` |
| `PAY_BETA02_20261015120000.csv` | FAILED | 4 | 0 | 0 | CONTROL_TOTAL_MISMATCH (file level; trailer 54300.00, rows sum to 54200.00) | FAILED only, header `reasonCode=CONTROL_TOTAL_MISMATCH` | `/error` + `.error.txt` |
| `PAY_BETA02_20261015130000.csv` | COMPLETED | 6 | 6 | 0 | none | RECEIVED, COMPLETED | `/processed` |

Control totals: 093000 and 110000 `435730.74`, 100000 `201918.42`, BETA02 120000 `54300.00` (declared), BETA02 130000 `2514095.50`.

## Rejected rows of `PAY_ACME01_20261015100000.csv`

Line numbers count the header as line 1. Account digits in `rawLine` are masked to the last four.

| Line | instruction_ref | Reason code | Why |
|---|---|---|---|
| 4 | ACME-0103 | INVALID_ACCOUNT | debtor account has 6 digits |
| 7 | ACME-0106 | INVALID_AMOUNT | amount is 0.00 |
| 10 | ACME-0109 | INVALID_CURRENCY | EUR |
| 14 | ACME-0113 | INVALID_VALUE_DATE | 2026-01-02 is in the past |
| 17 | ACME-0116 | CHANNEL_LIMIT_EXCEEDED | INTERAC 15,000.00 is over the 10,000.00 limit |

## Instruction outcomes (routing-service, M3)

| File | ROUTED | REQUIRES_REVIEW | DUPLICATE | By channel |
|---|---|---|---|---|
| `PAY_ACME01_20261015093000.csv` | 10 | 0 | 0 | EFT 5, WIRE 2, INTERAC 3 |
| `PAY_ACME01_20261015100000.csv` | 14 | 0 | 1 (ACME-0003, first sent in 093000) | EFT 8 (incl. the duplicate), WIRE 3, INTERAC 4 |
| `PAY_ACME01_20261015110000.csv` | 0 | 0 | 0 | none published |
| `PAY_BETA02_20261015120000.csv` | 0 | 0 | 0 | none published |
| `PAY_BETA02_20261015130000.csv` | 4 | 2 (BETA-0102 1,500,000.00 USD, BETA-0104 1,000,000.00 CAD) | 0 | EFT 3, WIRE 2, INTERAC 1 |

The duplicate `ACME-0003` gets one `DUPLICATE` status event with detail `already ROUTED`, carrying the batch id of the 100000 file it arrived in; it is not published to `payments.routed.eft` a second time, so the EFT topic holds 7 records for that file, not 8.

`PAY_BETA02_20261015130000.csv` re-uses the reference `ACME-0001` from ACME01. Keys are client scoped, so it is a new instruction for BETA02 ([ADR 0006](decisions/0006-sample-duplicate-instruction.md)).

## Totals across all five files

- Batches: 5 (2 COMPLETED, 1 COMPLETED_WITH_ERRORS, 2 FAILED)
- Instructions published to `payments.instructions`: 31
- Rows published to `payments.rejected`: 5
- Expected routing statuses: 28 ROUTED, 2 REQUIRES_REVIEW, 1 DUPLICATE

## What the API reports (api-service, M4)

`scripts/smoke-test.sh` asserts these through the API after all five files have settled. Verified by hand against a local run of the three services on the compose infrastructure.

### `GET /api/v1/stats/summary` (no range)

```json
{
  "batches": {"RECEIVED": 0, "COMPLETED": 2, "COMPLETED_WITH_ERRORS": 1, "FAILED": 2},
  "instructions": {"ROUTED": 28, "REQUIRES_REVIEW": 2, "DUPLICATE": 0, "DEAD_LETTERED": 0, "REPLAYED": 0},
  "duplicateDeliveries": 1,
  "rejectedRows": 5,
  "deadLetters": {"PENDING": 0, "REPLAY_REQUESTED": 0, "REPLAYED": 0, "EXHAUSTED": 0}
}
```

- 30 instruction rows, not 31: the duplicate `ACME-0003` never becomes a row of its own. It shows up as `duplicateDeliveries: 1`.
- Settled means `batches.RECEIVED` is 0 and `ROUTED + REQUIRES_REVIEW` is 30.
- No sample row uses `FORCE_FAIL`, so there are no dead letters.

### Per batch (`GET /api/v1/batches?clientId=...`, then `GET /api/v1/batches/{batchId}`)

| File | `status` | `totalRows` / `acceptedRows` / `rejectedRows` | `controlTotal` | `failureReason` | `instructionsByStatus` | `rejectionCount` |
|---|---|---|---|---|---|---|
| `PAY_ACME01_20261015093000.csv` | COMPLETED | 10 / 10 / 0 | 435730.74 | null | ROUTED 10 | 0 |
| `PAY_ACME01_20261015100000.csv` | COMPLETED_WITH_ERRORS | 20 / 15 / 5 | 201918.42 | null | ROUTED 14 | 5 |
| `PAY_ACME01_20261015110000.csv` | FAILED | 0 / 0 / 0 | null | DUPLICATE_FILE | all 0 | 0 |
| `PAY_BETA02_20261015120000.csv` | FAILED | 4 / 0 / 0 | 54300.00 | CONTROL_TOTAL_MISMATCH | all 0 | 0 |
| `PAY_BETA02_20261015130000.csv` | COMPLETED | 6 / 6 / 0 | 2514095.50 | null | ROUTED 4, REQUIRES_REVIEW 2 | 0 |

`deadLetterCount` is 0 for every batch. `GET /batches?clientId=ACME01` returns 3 batches, `clientId=BETA02` 2. Batches are listed newest first (`receivedAt` desc); `receivedAt` is when intake-service received the file, not the time in the file name.

### Rows and instructions

- `GET /batches/{id}/rejections` of the 100000 file: 5 rows, lines 4, 7, 10, 14, 17 with the reason codes in the table above, account digits in `rawLine` masked to the last four.
- `GET /batches/{id}/instructions` of the 100000 file: 14 instructions; `ACME-0003` is not among them because it belongs to the 093000 batch.
- `GET /instructions/{key}` of `ACME-0003` (`key = sha256("ACME01|ACME-0003")`): status `ROUTED`, `batchId` of the 093000 file, history `ROUTED` (093000 batch) then `DUPLICATE` with detail `already ROUTED` and the batch id of the 100000 file.
- Accounts are masked (`***0002`) for `operator`, and in full only for `admin` on `GET /instructions/{key}` (`accountsMasked: false`).

### Audit trail (`GET /api/v1/audit-events`, admin)

- Per accepted batch: one `BATCH_RECEIVED` and one `BATCH_COMPLETED` with `entityId` = batch id; the two FAILED files have only a `BATCH_COMPLETED` (`details.status` FAILED, `details.reasonCode`).
- Five `INSTRUCTION_REJECTED` documents with `entityId` = the 100000 batch id.
- Per instruction: one `INSTRUCTION_ROUTED` (routed record) and one `INSTRUCTION_STATUS_CHANGED` per status event; `ACME-0003` has two of the latter (ROUTED, DUPLICATE).
- Every login writes `LOGIN_SUCCESS` or `LOGIN_FAILURE` with actor `user:<username>`.

### Dead letters and replay with the chaos hook on (compose)

Not part of the sample set; a one-row file with `remittance_info` = `FORCE_FAIL` (the `docker` profile enables the chaos hook) gives:

1. About 3 s after routing: `GET /dead-letters` lists 1 dead letter, `status` PENDING, `replayCount` 0, `replayable` true, `exceptionClass` `ca.musakaleem.remit.routing.error.RoutingException`. The instruction is `DEAD_LETTERED`.
2. `POST /dead-letters/{id}/replay` as admin: 202, `status` REPLAY_REQUESTED, `replayCount` 1. A second replay right away: 409 (pending).
3. The replay fails again (chaos is still on); after about 5 s the same dead letter (still 1 row) is PENDING with `replayCount` 1.
4. After the third replay fails it is EXHAUSTED with `replayCount` 3, and a fourth replay is 409 `Dead letter <id> reached the limit of 3 replays`.
5. The instruction ends `DEAD_LETTERED` with `replayCount` 3 and four `DEAD_LETTERED` history entries; the audit trail has three `DLT_REPLAY_REQUESTED` documents with actor `user:admin`.
