# ADR 0004: JWT approach for the operator API

- Status: accepted
- Date: 2026-09-30

## Context

FR-SEC-01 asks for a stateless Spring Security 6 filter chain with HS256 JSON Web Tokens signed with a secret from `REMIT_JWT_SECRET` (at least 32 bytes, startup fails otherwise), a 15 minute expiry and the claims `sub`, `roles`, `iat`, `exp` and `jti`. The spec allows either jjwt or Spring Security's `oauth2-resource-server` with a symmetric key. FR-SEC-02 asks for BCrypt users seeded for the local profile only, with passwords from the environment. FR-SEC-06 asks for a lockout after five failed logins in ten minutes.

api-service both issues and verifies its tokens; there is no separate authorization server.

## Decision

### Library: jjwt 0.12

We use `io.jsonwebtoken:jjwt-api`, `jjwt-impl` and `jjwt-jackson` 0.12.x and a small custom filter, not `oauth2-resource-server`:

- `oauth2-resource-server` is built to verify tokens that someone else issues. Issuing our own tokens would still need `spring-security-oauth2-jose`, a `NimbusJwtEncoder` with a JWK source and a `JwtAuthenticationConverter` to turn a `roles` claim into authorities. That is more configuration than code, and harder to explain line by line.
- With jjwt, `JwtService` issues and parses tokens in about a hundred readable lines, and `JwtAuthenticationFilter` turns a verified token into an authentication with `ROLE_<role>` authorities. Both are unit tested directly.
- jjwt accepts an injected clock, so expiry is tested with a fixed clock instead of sleeping.

### Signing

- HS256 with the UTF-8 bytes of `REMIT_JWT_SECRET`. `JwtService` refuses to start when the variable is missing or blank, or shorter than 32 bytes (256 bits, the HS256 minimum), with a message that names the variable and how to generate one (`openssl rand -base64 48`).
- The algorithm is pinned on both sides. jjwt would pick HS384 or HS512 for a longer key if asked to choose; `signWith(key, Jwts.SIG.HS256)` and a header check on parse keep it HS256. Unsigned (`alg: none`) tokens are rejected by `parseSignedClaims`.

### Claims and lifetime

| Claim | Value |
|---|---|
| `sub` | username |
| `roles` | array of role names, for example `["ADMIN","OPERATOR"]` |
| `iat` | issue time |
| `exp` | `iat` + 15 minutes (`remit.security.jwt.ttl`) |
| `jti` | random UUID per token |

A token without a subject, roles, `iat` or `exp`, with a bad signature, or expired is answered with 401 and `WWW-Authenticate: Bearer error="invalid_token"`. There is no refresh token: clients log in again after 15 minutes. There is no `iss` or `aud` claim because only this service issues and accepts the tokens.

### Stateless sessions

No HTTP session, no CSRF (no cookies), no form or basic login. Nothing about a token is stored on the server. `jti` would allow a deny list later; for now a token stays valid until it expires (at most 15 minutes), even if its user is disabled. Disabling a user blocks new logins.

### Roles

`OPERATOR` reads everything; `ADMIN` replays dead letters and reads the audit trail (FR-SEC-03). Roles are not hierarchical: the seeded `admin` holds both roles. Endpoints use `@PreAuthorize("hasRole('...')")` with `@EnableMethodSecurity`.

### Users and seeding

Users live in `app_user` and `app_user_role` with BCrypt hashes (strength 10). The two demo users are created by `UserSeeder`, a Java `ApplicationRunner`, not by a Flyway `V2` migration:

- A SQL migration cannot BCrypt a password read from the environment at run time. It would need a precomputed hash committed to the repository or passed as a placeholder, which defeats "passwords come from env vars, never committed".
- Flyway migrations are meant to run the same way in every environment; seeding is a per-environment decision.

`UserSeeder` runs when `remit.security.seed-users.enabled` is true. It is true in the `local` and `docker` profiles (the spec's "local profile", plus the compose stack that the smoke test and Newman log in to) and false otherwise. `REMIT_SEED_USERS_ENABLED=true` turns it on elsewhere, for example on a local kind cluster. It creates `operator` (OPERATOR) and `admin` (OPERATOR, ADMIN) from `REMIT_OPERATOR_PASSWORD` and `REMIT_ADMIN_PASSWORD` only if they do not exist, never changes an existing user, and fails startup if a password it needs is not set.

### Login lockout

`LoginAttemptService` keeps failure times per lower-cased username in memory: five failures within ten minutes lock the username for ten minutes. A locked login is answered with 429 and `Retry-After`, without checking the password. Unknown usernames are counted the same way, so the answer never reveals whether an account exists. Every attempt writes a `LOGIN_SUCCESS` or `LOGIN_FAILURE` audit document with the reason (`BAD_CREDENTIALS`, `DISABLED`, `LOCKED`).

## Consequences

- The lockout is per instance and lost on restart, which FR-SEC-06 allows. With N replicas an attacker gets 5 x N attempts per ten minutes; a shared store (MySQL or Redis) would fix that.
- There is one signing key and no `kid` header, so rotating `REMIT_JWT_SECRET` invalidates every issued token at once. For a 15 minute token that is acceptable; see "Rotate the JWT secret" in the runbook.
- Swapping to `oauth2-resource-server` later would only replace `JwtService` and `JwtAuthenticationFilter`; the claims, roles and `@PreAuthorize` rules stay the same.
