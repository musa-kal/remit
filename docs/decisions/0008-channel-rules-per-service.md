# ADR 0008: Channel rules are configured per service with the same keys

- Status: accepted
- Date: 2026-09-30

## Context

Spec 3.3 defines the channel rules (INTERAC: CAD only, at most 10,000.00; EFT: at most 25,000,000.00; WIRE: no maximum) and says they are configured under `remit.routing.rules`. intake-service applies them while validating rows. routing-service applies them again defensively (FR-RTE-02 step 3), because it also receives replays that intake never saw. Only routing-service uses the WIRE review threshold.

intake-service binds the rules with its own `RoutingRulesProperties` record. Moving that record to `remit-common` would make the shared library depend on Spring Boot's `@ConfigurationProperties`, which ADR 0001 keeps out of it.

## Decision

- routing-service binds `remit.routing` with its own `RoutingProperties` record. Its `rules` map has exactly the same shape as intake's (`remit.routing.rules.<CHANNEL>.max-amount` and `.currencies`), so the same YAML block, environment variables or ConfigMap entries configure both services.
- routing-service adds `remit.routing.review-threshold` (default 1000000.00), `remit.routing.chaos-enabled` and `remit.routing.publish-timeout`. intake-service ignores them.
- Both `application.yml` files carry the same illustrative defaults.

## Consequences

- A change to a limit must be made in both services' configuration. In Kubernetes and Compose that is one shared value; in the two `application.yml` files it is two edits, kept side by side with the same comment.
- If the two services disagree, routing-service is the stricter gate: an instruction intake accepted but routing rejects is dead-lettered with `NonRetryableRoutingException` and visible to operators, not silently routed.
