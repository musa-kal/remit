# ADR 0006: Where the sample files show a duplicate instruction

- Status: accepted
- Date: 2026-09-30

## Context

Spec 3.7 asks for `PAY_BETA02_20261015130000.csv` to contain "one row re-using an instruction_ref from an earlier file (duplicate instruction)". Spec 3.6 defines the instruction key as `SHA-256(clientId + "|" + instruction_ref)`, so a reference is only a duplicate when the same client sends it again.

BETA02's only earlier sample file, `PAY_BETA02_20261015120000.csv`, is rejected at file level (control total mismatch), so none of its rows are ever published. A BETA02 file therefore cannot contain a true duplicate of an earlier published instruction. Re-using a reference from an ACME01 file produces a different key, so routing-service will route it as a new instruction.

## Decision

- `PAY_BETA02_20261015130000.csv` re-uses `ACME-0001` from `PAY_ACME01_20261015093000.csv`, as the spec describes. Because keys are client scoped, this row demonstrates that two clients may use the same reference: it is published with key `SHA-256("BETA02|ACME-0001")` and is expected to be ROUTED, not DUPLICATE.
- To still exercise the cross-file duplicate path end to end, `PAY_ACME01_20261015100000.csv` (20 rows, 5 invalid) contains one valid row that repeats `ACME-0003` from `PAY_ACME01_20261015093000.csv` with the same content. It passes intake validation (intake never compares rows across files) and routing-service is expected to report it as `DUPLICATE`.
- Within-file repeats are a different rule: intake rejects the second and later rows with `DUPLICATE_INSTRUCTION_REF_IN_FILE`. The sample files do not contain one; unit tests cover it.

## Consequences

- `docs/e2e-expectations.md` lists, for the 100000 file, 15 accepted rows of which 14 are expected ROUTED and 1 DUPLICATE, and for the BETA02 130000 file 6 ROUTED or REQUIRES_REVIEW with no duplicates.
- The spec's example is kept literally, and the duplicate path is still visible in a full demo run.
