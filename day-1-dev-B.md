# Day 1 — Dev B — Security, user provisioning, configuration

## You have no prior context. Read this whole file before doing anything.

You are **Dev B** on a three-person team building **Protify**, a Portfolio Management REST API
(Java 21, Spring Boot 3.5.16, MySQL 8.4, `NamedParameterJdbcTemplate` — **no JPA**) plus a
React frontend. Base package `com.protify.portfolio`.

**You own:** `portfolio-platform` (`security`, `user`, `marketdata`, `fx`, `config`, `web`),
`scripts/`, `docker/`, `Jenkinsfile`, Flyway `V10`–`V19`.

**Today you build the thing every other endpoint sits behind.** By tonight, a real Google
account signs in through a browser and a user row appears in MySQL.

**Read first:** `/CLAUDE.md` · `/docs/PLAN.md` §2, §3 · `/docs/REFERENCE_DESIGN.md` §6 ·
`/docs/DECISIONS/0004-google-id-token-as-bearer.md` · `/docs/API_CONTRACT.md` §0.1, §0.7.

**From Day 0 you should have:** a Google OAuth Web client ID, and a real ID token saved to a
scratch file. If you do not, get them first — everything below is blocked without them.

---

## D1-B1 · OAuth2 resource server — 2.0 h · 🔴 critical path

`portfolio-platform/…/platform/security/SecurityConfig.java`

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://accounts.google.com
          audiences: ${GOOGLE_CLIENT_ID}
```

Spring fetches Google's JWKS and validates signature, issuer, audience and expiry. **Write no
JWT parsing yourself.** Every historical JWT vulnerability — `alg: none`, key confusion,
missing audience check — comes from hand-rolled validation.

Security rules:
- Everything under `/api/v1/**` requires authentication.
- Permit: `/actuator/health`, `/v3/api-docs/**`, `/swagger-ui/**`.
- Stateless — `SessionCreationPolicy.STATELESS`, CSRF disabled (there is no cookie to protect).
- CORS for `http://localhost:5173` with the `Authorization` header allowed.
- **401 for missing or invalid token; 403 only for an authenticated user who is not permitted.** Do not conflate them.

**Tests — `SecurityConfigTest`:** four *distinct* failure reasons must be distinguishable in
the logs — bad signature, wrong `aud`, wrong `iss`, expired. A 401 that could mean any of four
things costs an hour of debugging later, and this is `/docs/RISKS.md` R6's main mitigation.
Use a locally-generated RSA key pair and a stub JWK source for tests; **no test calls Google.**

**`UnauthorisedAccessIT`:** no header → 401. Garbage header → 401. Valid-shaped token signed by
the wrong key → 401. Every response body is a clean `ProblemDetail` with no stack trace.

---

## D1-B2 · `CurrentUserResolver` and JIT provisioning — 2.0 h · 🔴 critical path

`portfolio-platform/…/platform/user/`

- `AppUserRepository` — `NamedParameterJdbcTemplate`, explicit SQL, hand-written `AppUserRowMapper`. Use Dev A's `BaseRepository` for the generated-key insert.
- `CurrentUserResolver` — takes the validated `Jwt`, reads `sub`, looks up `app_user`, **creates the row on first sight**, exposes the **internal** `userId` as a request-scoped bean.

Rules:
- **Reject `email_verified: false` with 403.** An unverified email claim is attacker-controlled.
- The Google `sub` never reaches a repository beyond this lookup. Everything downstream uses the internal `userId`. This matters: `sub` is an external identifier and `user_id` is ours.
- Handle the concurrent-first-sign-in race: two simultaneous requests with the same new `sub`. Catch the unique-constraint violation on `google_sub` and re-read. **Exactly one row must exist.**

**Tests:** `CurrentUserResolverTest` (unit, mocked repo) · `JitProvisioningIT` — first call
creates a row, second creates none, unverified email creates none and gives 403, two
concurrent first calls create exactly one row.

---

## D1-B3 · Configuration and secrets — 1.0 h · 🔴

`portfolio-api/src/main/resources/application.yml` + `application-local.yml` + `/.env.example`

> **Note:** `application.yml` lives in `portfolio-api`, which is Dev C's module. Agree this one
> file with Dev C this morning — it is the standard exception, since the runnable app owns its
> configuration. Do not touch anything else in `portfolio-api`.

Every secret is an env var with a safe local default:

```
GOOGLE_CLIENT_ID=          # public by design, not a secret
DB_URL=jdbc:mysql://localhost:3306/portfolio?serverTimezone=UTC
DB_USERNAME=protify
DB_PASSWORD=
MARKET_DATA_PROVIDER=yahoo
TWELVE_DATA_API_KEY=       # optional; the provider disables itself when absent
FX_PROVIDER=frankfurter
ADMIN_EMAILS=
FEATURES_INSIGHTS_ENABLED=false
```

**`?serverTimezone=UTC` on the JDBC URL is not optional.** Without it `DATETIME` values shift
silently between JVM and database, and every date assertion becomes flaky in a way that looks
like a logic bug (`/docs/RISKS.md` R13).

**Test — `ConfigurationSmokeTest`:** the context loads with only the defaults set, and `git
grep` finds no live-looking key in the repository.

---

## D1-B4 · Correlation-ID filter — 1.0 h

`portfolio-platform/…/platform/web/CorrelationIdFilter.java`

Read `X-Correlation-Id` from the request or mint a UUID. Put it in the SLF4J MDC. Echo it on
the response header. Order it **before** the security filter chain, so a 401 is traceable too —
that is exactly the failure you most want to be able to look up.

Dev C's `GlobalExceptionHandler` reads the same value into every `ProblemDetail`. Agree the MDC
key with them: `correlationId`.

**Test — `CorrelationIdFilterTest`:** a supplied id is echoed; an absent one is generated; the
MDC is cleared after the request (a leak means the next request on that pooled thread logs the
wrong id).

---

## D1-B5 · Logging configuration — 0.5 h · 🟢 can slip

`logback-spring.xml` — pattern including `%X{correlationId}`, `DEBUG` on `com.protify` in the
local profile, `INFO` otherwise. Never log a token, a password or a full JWT.

---

## Rules

- **No JPA, no Hibernate, no Spring Data.** Explicit SQL, hand-written `RowMapper`s.
- **No `double`, no `float`.**
- No `JdbcTemplate` outside a `*Repository`.
- **You may not edit `pom.xml`.** Need a dependency? Ask Dev A, who pushes it to `develop`.
- Migrations only in `V10`–`V19`. You need none today.
- **Real Google auth, not a mock.** A local mock issuer is permitted only as the `/docs/RISKS.md` R6 fallback for unblocking others, never as the deliverable.

## Do not touch

`portfolio-core/**` (Dev A) · `portfolio-api/**` except `application*.yml` as agreed (Dev C) ·
`portfolio-common/**` — **it freezes tonight** · any POM · migrations `V1`–`V9`, `V20`+.

---

## Done when

- [ ] A **real** Google ID token gets 200 from a protected endpoint
- [ ] A tampered, expired, wrong-`aud` and wrong-`iss` token each get 401, distinguishable in the logs
- [ ] First sign-in creates one `app_user` row; the second creates none
- [ ] `email_verified: false` gets 403 and creates no row
- [ ] Two concurrent first sign-ins create exactly one row
- [ ] Correlation ID present on every response, including 401s
- [ ] No secret in the repository; `.env.example` complete
- [ ] `mvn clean verify` green; merged to `develop` by 17:30

## Hand-off

Tell Dev C how to obtain the `userId` from your resolver — they need it in every controller
tomorrow.

Tomorrow you build market data and FX: two provider adapters, a Caffeine-backed caching
service with a `price_history` fallback, `V10__fx_rate.sql`, and the Python price backfill.
**You depend on Dev A's ports (`MarketDataProvider`, `FxRateProvider`) landing tonight.** If
they are not merged by 14:00 today, escalate at stand-up — your whole Day 2 compiles against
them.
