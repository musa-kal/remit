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
