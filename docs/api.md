# Remit operator API

api-service serves the operator REST API on port 8080 (`REMIT_API_PORT`). All paths start with `/api/v1`. The OpenAPI document is served at `/v3/api-docs`, Swagger UI at `/swagger-ui.html`, and the build exports the same document to [docs/openapi.json](openapi.json).

## Endpoints

| Method | Path | Role | Success | Description |
|---|---|---|---|---|
| POST | `/auth/login` | public | 200 | Body `{username, password}`, returns `{accessToken, tokenType: "Bearer", expiresIn: 900}` |
| GET | `/batches` | OPERATOR | 200 | Paged batches, newest first. Filters `status`, `clientId`, `from`, `to` (on `receivedAt`) |
| GET | `/batches/{batchId}` | OPERATOR | 200 | Batch with counts, control total, instructions per status, rejection and dead-letter counts |
| GET | `/batches/{batchId}/instructions` | OPERATOR | 200 | Paged instructions of a batch, sorted by `instructionRef`. Filters `status`, `channel` |
| GET | `/batches/{batchId}/rejections` | OPERATOR | 200 | Paged rejected rows with reason codes, sorted by `lineNumber` |
| GET | `/instructions/{idempotencyKey}` | OPERATOR | 200 | Instruction detail and status history; full account numbers for ADMIN only |
| GET | `/dead-letters` | OPERATOR | 200 | Paged dead letters, newest first. Filter `status` |
| POST | `/dead-letters/{id}/replay` | ADMIN | 202 | Republish the stored instruction with `replayCount + 1` |
| GET | `/audit-events` | ADMIN | 200 | Paged audit documents, newest first. Filters `entityId`, `eventType`, `from`, `to` |
| GET | `/stats/summary` | OPERATOR | 200 | Counts per status. Optional `from`, `to` |

Roles are not hierarchical; the seeded `admin` user holds both OPERATOR and ADMIN. Also public: `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/info`, `/swagger-ui.html`, `/v3/api-docs`. `/actuator/metrics` needs any valid token.

## Authentication

1. `POST /api/v1/auth/login` with a username and password.
2. Send the `accessToken` as `Authorization: Bearer <token>` on every other call.
3. The token is an HS256 JWT valid for 15 minutes (`sub`, `roles`, `iat`, `exp`, `jti`); log in again when it expires. There is no refresh token.

Five failed logins for one username within 10 minutes lock that username for 10 minutes (429 with `Retry-After`, even for the right password). The lock is kept in memory per api-service instance. See [ADR 0004](decisions/0004-jwt-approach.md).

In the `local` and `docker` profiles api-service creates two users at startup if they do not exist: `operator` (OPERATOR) with `REMIT_OPERATOR_PASSWORD` and `admin` (OPERATOR, ADMIN) with `REMIT_ADMIN_PASSWORD`.

## Examples

The outputs below come from a local run with the five sample files and one extra `FORCE_FAIL` row; ids are shortened with `...`.

```bash
set -a; . ./.env; set +a
TOKEN=$(curl -s -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$REMIT_ADMIN_PASSWORD\"}" | jq -r .accessToken)
```

```json
{"accessToken": "eyJhbGciOiJIUzI1NiJ9...", "tokenType": "Bearer", "expiresIn": 900}
```

### Batches

```bash
curl -s "localhost:8080/api/v1/batches?clientId=ACME01&size=1" -H "Authorization: Bearer $TOKEN"
```

```json
{
  "content": [
    {
      "batchId": "432a3e44-...",
      "clientId": "ACME01",
      "fileName": "PAY_ACME01_20261015100000.csv",
      "fileHash": "908467fd...",
      "status": "COMPLETED_WITH_ERRORS",
      "totalRows": 20,
      "acceptedRows": 15,
      "rejectedRows": 5,
      "controlTotal": 201918.42,
      "failureReason": null,
      "receivedAt": "2026-09-30T18:05:08.356Z",
      "completedAt": "2026-09-30T18:05:08.780Z",
      "updatedAt": "2026-09-30T18:05:08.780Z"
    }
  ],
  "page": 0,
  "size": 1,
  "totalElements": 3,
  "totalPages": 3
}
```

`GET /batches/{batchId}` returns the same fields plus:

```json
{
  "instructionsByStatus": {"ROUTED": 14, "REQUIRES_REVIEW": 0, "DUPLICATE": 0, "DEAD_LETTERED": 0, "REPLAYED": 0},
  "rejectionCount": 5,
  "deadLetterCount": 0
}
```

`failureReason` is the `reasonCode` of a FAILED batch, for example `DUPLICATE_FILE` or `CONTROL_TOTAL_MISMATCH`.

### Instructions of a batch (always masked)

```bash
curl -s "localhost:8080/api/v1/batches/$BATCH/instructions?status=ROUTED&size=1" -H "Authorization: Bearer $TOKEN"
```

```json
{
  "content": [
    {
      "idempotencyKey": "01c6ec0c...",
      "batchId": "432a3e44-...",
      "clientId": "ACME01",
      "instructionRef": "ACME-0101",
      "debtor": {"name": "Acme Widgets Ltd", "institution": "001", "transit": "00011", "account": "***0001"},
      "creditor": {"name": "Northwind Supplies", "institution": "002", "transit": "00022", "account": "***0001"},
      "amount": 500.00,
      "currency": "CAD",
      "channel": "EFT",
      "valueDate": "2026-10-15",
      "remittanceInfo": "Invoice 2001",
      "status": "ROUTED",
      "statusDetail": null,
      "replayCount": 0,
      "updatedAt": "2026-09-30T18:05:08.724Z"
    }
  ],
  "page": 0, "size": 1, "totalElements": 14, "totalPages": 14
}
```

Payment details (`debtor`, `amount`, ...) are `null` until the routed record or the dead-letter record carrying them has been consumed.

### Rejected rows

```json
{
  "content": [
    {
      "id": 1,
      "batchId": "432a3e44-...",
      "lineNumber": 4,
      "instructionRef": "ACME-0103",
      "reasonCodes": ["INVALID_ACCOUNT"],
      "rawLine": "ACME-0103,Acme Widgets Ltd,001,00011,123456,Prairie Grain Traders,006,00066,***0004,410.00,CAD,EFT,2026-10-15,Bad debtor account",
      "occurredAt": "2026-09-30T18:05:08.519Z"
    }
  ],
  "page": 0, "size": 20, "totalElements": 5, "totalPages": 1
}
```

### Instruction detail and history

```bash
KEY=$(printf 'ACME01|ACME-0003' | sha256sum | cut -d' ' -f1)
curl -s localhost:8080/api/v1/instructions/$KEY -H "Authorization: Bearer $TOKEN"
```

For an OPERATOR token (an ADMIN token gets the full account numbers and `"accountsMasked": false`):

```json
{
  "idempotencyKey": "11a3bdce...",
  "batchId": "0ba6884e-...",
  "instructionRef": "ACME-0003",
  "debtor": {"name": "Acme Widgets Ltd", "institution": "001", "transit": "00011", "account": "***0002"},
  "status": "ROUTED",
  "accountsMasked": true,
  "history": [
    {"status": "ROUTED", "batchId": "0ba6884e-...", "channel": "EFT", "detail": null, "occurredAt": "2026-09-30T18:05:08.333Z"},
    {"status": "DUPLICATE", "batchId": "432a3e44-...", "channel": "EFT", "detail": "already ROUTED", "occurredAt": "2026-09-30T18:05:09.043Z"}
  ]
}
```

(other fields as in the list above). A duplicate delivery never changes the current status; it shows up in the history with the batch it arrived in.

### Dead letters and replay

```bash
curl -s localhost:8080/api/v1/dead-letters -H "Authorization: Bearer $TOKEN"
```

```json
{
  "content": [
    {
      "id": 1,
      "idempotencyKey": "52eae13f...",
      "batchId": "eee61925-...",
      "originalTopic": "payments.instructions",
      "originalPartition": 2,
      "originalOffset": 25,
      "exceptionClass": "ca.musakaleem.remit.routing.error.RoutingException",
      "exceptionMessage": "Listener method '...' threw exception; Forced failure: remittanceInfo is FORCE_FAIL and the chaos hook is enabled",
      "status": "PENDING",
      "replayCount": 0,
      "replayable": true,
      "firstSeenAt": "2026-09-30T18:06:54.384Z",
      "lastReplayedAt": null,
      "updatedAt": "2026-09-30T18:06:54.384Z"
    }
  ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1
}
```

```bash
curl -s -X POST localhost:8080/api/v1/dead-letters/1/replay -H "Authorization: Bearer $TOKEN"
```

```json
{"id": 1, "idempotencyKey": "52eae13f...", "batchId": "eee61925-...", "status": "REPLAY_REQUESTED", "replayCount": 1, "requestedAt": "2026-09-30T18:07:23.041Z"}
```

Dead letter statuses: `PENDING` (replayable), `REPLAY_REQUESTED` (published, waiting for routing-service), `REPLAYED` (routed after a replay), `EXHAUSTED` (replayed 3 times without success). A replay is refused with 409 when a replay is pending, the dead letter was already replayed successfully, `replayCount` is 3 (the dead letter is marked EXHAUSTED), or the record is unreadable (a poison pill). The [runbook](runbook.md#replay-a-dlt-message) walks through a replay.

### Audit events (ADMIN)

```bash
curl -s "localhost:8080/api/v1/audit-events?entityId=$KEY_OF_THE_DEAD_LETTER&eventType=DLT_REPLAY_REQUESTED" \
  -H "Authorization: Bearer $TOKEN"
```

```json
{
  "content": [
    {
      "id": "66fae9f3...",
      "eventId": "c1b0...",
      "eventType": "DLT_REPLAY_REQUESTED",
      "entityType": "INSTRUCTION",
      "entityId": "52eae13f...",
      "actor": "user:admin",
      "occurredAt": "2026-09-30T18:07:23.041Z",
      "correlationId": "eee61925-...",
      "details": {"deadLetterId": 1, "replayCount": 1, "maxReplays": 3}
    }
  ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1
}
```

Event types: `BATCH_RECEIVED`, `BATCH_COMPLETED`, `INSTRUCTION_ROUTED`, `INSTRUCTION_STATUS_CHANGED`, `INSTRUCTION_REJECTED`, `INSTRUCTION_DEAD_LETTERED`, `DEAD_LETTER_RECORDED`, `DLT_REPLAY_REQUESTED`, `LOGIN_SUCCESS`, `LOGIN_FAILURE` ([ADR 0009](decisions/0009-audit-event-types.md)).

### Stats

```bash
curl -s "localhost:8080/api/v1/stats/summary" -H "Authorization: Bearer $TOKEN"
```

After the five sample files on an empty stack:

```json
{
  "from": null,
  "to": null,
  "batches": {"RECEIVED": 0, "COMPLETED": 2, "COMPLETED_WITH_ERRORS": 1, "FAILED": 2},
  "instructions": {"ROUTED": 28, "REQUIRES_REVIEW": 2, "DUPLICATE": 0, "DEAD_LETTERED": 0, "REPLAYED": 0},
  "duplicateDeliveries": 1,
  "rejectedRows": 5,
  "deadLetters": {"PENDING": 0, "REPLAY_REQUESTED": 0, "REPLAYED": 0, "EXHAUSTED": 0}
}
```

`batches` counts by `receivedAt`, `instructions` by the time of their current status, `duplicateDeliveries` counts DUPLICATE status events, `deadLetters` counts by `firstSeenAt`. Every status is always present.

## Paging, sorting and filters

- `page` (zero-based, default 0), `size` (default 20, **values above 100 are clamped to 100**; the response's `size` shows the value used) and `sort=property,asc|desc`.
- Allowed sort properties: batches `receivedAt`, `completedAt`, `clientId`, `status`, `fileName`; batch instructions `instructionRef`, `amount`, `status`, `channel`, `updatedAt`, `idempotencyKey`; rejections `lineNumber`, `occurredAt`; dead letters `firstSeenAt`, `updatedAt`, `status`, `replayCount`, `id`; audit events `occurredAt`, `eventType`, `entityId`. Any other property is a 400.
- `from` and `to` are ISO-8601 instants (`2026-10-15T00:00:00Z`); `from` is inclusive, `to` exclusive, and `from` must be before `to`.
- `clientId` must be 3 to 10 upper-case letters or digits; `idempotencyKey` 64 lower-case hex characters; enum filters must be one of the listed values.

## Errors

Every error is an RFC 7807 `application/problem+json` body without stack traces or exception names:

```json
{"type": "about:blank", "title": "Unauthorized", "status": 401, "detail": "Authentication is required: send a bearer token", "instance": "/api/v1/batches"}
```

| Status | When |
|---|---|
| 400 | Invalid body or parameter. The body lists the fields: `"errors": [{"field": "size", "message": "..."}]` |
| 401 | No token, or an invalid or expired token (`WWW-Authenticate: Bearer error="invalid_token"`, detail `The access token has expired`), or a failed login (`Invalid username or password`) |
| 403 | Valid token without the required role (`Your roles do not allow this operation`) |
| 404 | Unknown batch, instruction, dead letter or path |
| 409 | Replay refused (see above) |
| 429 | Login for a locked username, with `Retry-After` in seconds |
| 503 | Kafka did not acknowledge a replay; nothing changed, try again |

Every response carries an `X-Correlation-Id` header (the request's own if it sent a well formed one); the same id is in the JSON log lines of that request.

## Account masking

Account numbers are masked to their last four digits in every response and every audit document. The only exception is `GET /instructions/{idempotencyKey}` for a caller with the ADMIN role (FR-SEC-05).
