# CLAUDE.md — Protify Portfolio Manager (backend)

Conventions for every contributor, human or agent. Claude Code loads this automatically
because it sits at the repository root. Read it before writing code. Keep it short;
justifications belong in `/docs/DECISIONS/`.

> **Where the docs live.** `/docs/` is **git-ignored on purpose** — it is not pushed to
> GitHub. Every collaborator already has the folder on their machine at the same relative
> path (`<repo>/docs/`). If your `/docs/` is missing or stale, get it from the team, do
> not regenerate it. This file (`/CLAUDE.md`) **is** tracked and pushed; it is the only
> planning artefact in git.

## What this is

A Portfolio Management REST API plus a React frontend (separate repo). Users sign in with
Google, browse a portfolio, view performance graphically, and add and remove items.
**Transaction-centric:** transactions are the source of truth, holdings are a projection.

Team of 3. Day 0 is setup; Days 1–6 are build. **MVP by end of Day 3**, showcase on Day 6.

- Shared contract: `/docs/REFERENCE_DESIGN.md` — schema, enums, domain rules, API, ownership
- **Amendments to it:** `/docs/PLAN.md` §2 "Deviations from REFERENCE_DESIGN" — **binding, read it**
- Plan: `/docs/PLAN.md` · API detail: `/docs/API_CONTRACT.md` · Tests: `/docs/TEST_PLAN.md`
- Your daily brief: `/docs/PHASE_PROMPTS/day-<N>-dev-<X>.md`

**Current day: `<0-6>` · I am: `<Dev A / B / C>`** — update both at the start of each session.

## Stack

| | |
|---|---|
| Java | 21 |
| Spring Boot | 3.5.16 — do not mix in Spring Boot 4 / Framework 7 idioms |
| Build | Maven, single module, the one POM owns all versions |
| Persistence | `NamedParameterJdbcTemplate` — **no JPA, no Hibernate, no Spring Data** |
| Database | MySQL 8.4, Flyway migrations |
| Auth | Spring Security OAuth2 **Resource Server**, issuer `https://accounts.google.com` |
| Tests | JUnit 5, Mockito, AssertJ, MockMvc, Testcontainers (MySQL) |
| API docs | springdoc-openapi 2.8.x |
| Market data | Real prices via `MarketDataProvider` port; Caffeine cache; `price_history` fallback |
| FX | Real daily rates via `FxRateProvider` port; `fx_rate` table; last-good fallback |
| Containers | Multi-stage Dockerfile + compose. Runs in the Linux VM (or Docker Desktop) |
| CI/CD | Jenkins on the Linux VM is the pipeline of record; GitHub Actions mirrors it |

## Package layout

Single Maven module, one deployable jar. Base package: `com.protify.portfolio`. This was a
5-module Maven reactor through Day 1; collapsed to one module the same day — see ADR-0012
(supersedes ADR-0003's module-graph-enforcement clause; the modular-monolith-not-microservices
call itself stands).

```
common/            MoneyUtils, Money, enums, DomainException tree, ports        [Dev A · FROZEN after Day 1]
support/           BaseRepository — the one class that constructs a KeyHolder   [Dev A]
config/            beans, RestClient, cache, OpenAPI                            [Dev B]
security/          resource-server config, token→user resolution                [Dev B]
user/              AppUser, repository, JIT provisioning                        [Dev B]
marketdata/        provider adapters, cache, scheduler                         [Dev B]
fx/                FX adapters, cache                                           [Dev B]
instrument/        Instrument, repository, search                               [Dev A]
portfolio/         Portfolio, controller, service, repository                   [Dev A]
transaction/       Txn, controller, service, repository, projection rebuild     [Dev A]
holding/           Holding, repository, ProjectionEngine                        [Dev A]
valuation/         valuation engine, performance series, allocation             [Dev A]
api/               PortfolioApplication, dto/, mapper/, GlobalExceptionHandler  [Dev C]
graphql/           schema + resolvers                                           [Dev C]
```

Flyway migrations are resources, not code — `src/main/resources/db/migration/`. Still the one
directory everyone touches: add files, never edit someone else's (ranges unchanged, see below).

**Package boundaries are convention now, not compiled.** `core → platform` used to be a
non-existent Maven dependency, so a wrong import was a compile error. Now nothing stops
`transaction`/`holding`/`valuation` code from importing a `marketdata`/`fx` class or an HTTP
client directly — it should only ever reach market data and FX through the
`MarketDataProvider` / `FxRateProvider` interfaces in `common`. Catch a violation in review; the
compiler no longer will.

## Non-negotiables

1. **Money is `BigDecimal`.** `DECIMAL(19,4)` money, `DECIMAL(19,6)` quantities,
   `DECIMAL(19,8)` FX rates. Never `double` or `float`. Compare with `compareTo`, never
   `equals` or `==`. Rounding scale and mode live once, in `MoneyUtils`.
2. **No JPA, no Hibernate, no Spring Data.** Explicit SQL, hand-written `RowMapper`s.
3. **Every query touching user data filters by the authenticated user's ID.** A
   repository method that *can* return another user's row is a bug even if no controller
   currently calls it that way.
4. **Schema changes are new migrations in my own number range** — Dev A `V1`–`V9`,
   Dev B `V10`–`V19`, Dev C `V20`–`V29`. Never edit an applied migration.
   `src/main/resources/db/migration/` is the one directory everyone touches: **add files,
   never touch someone else's.**
5. **DTOs are `record`s** with Bean Validation. Persistence types never cross the
   controller boundary in either direction.
6. **`controller → service → repository`.** No business logic in controllers or
   repositories. No `JdbcTemplate` outside repositories.
7. **All errors through `GlobalExceptionHandler`** as RFC 9457 `ProblemDetail` with a
   correlation ID. No stack traces, SQL or internal class names in responses. Never
   swallow an exception.
8. **Every endpoint:** happy path, validation failure, not found, unauthorised tests.
9. **UTC internally.** Every money value in a response carries its ISO-4217 currency code.
10. **No external call without a fallback.** The demo must work with no internet:
    provider → Caffeine cache → `price_history` / `fx_rate` last-good row → seeded data.
11. **No secrets in the repo.** Env vars with local defaults; `.env.example` is tracked,
    `.env` is not.
12. **The holdings projection is recomputed inside the same DB transaction** as the
    transaction write. It must be reproducible by a full rebuild from `txn`.
13. **Stored values are in native currency; base currency is a presentation concern.**
    `holding.avg_cost` is in the instrument's currency. Converting to the portfolio's
    base currency happens in the valuation layer at read time, never in a repository.

## Naming

- `*Controller`, `*Service`, `*Repository`, `*RowMapper`, `*Mapper`
- DTOs: `Create*Request`, `Update*Request`, `*Response`
- Exceptions: `*NotFoundException`, `*ValidationException`, `*UpstreamException`
- Tests mirror the class: `TransactionServiceTest`, `HoldingRepositoryIT` (`*IT` = runs in `verify`)
- Test methods read as sentences: `shouldRejectSellWhenQuantityExceedsHolding`
- SQL: `snake_case`, singular tables, `id` PKs, `*_id` FKs, `created_at`/`updated_at`
- Enums persisted as `name()` strings, never ordinals; unknown values handled explicitly

## Commands

```bash
mvn clean verify                        # build + tests + coverage gate — green before any PR
mvn spring-boot:run
mvn clean verify -DskipITs              # fast loop, no Docker needed
git pull origin develop                 # first thing every morning
docker compose up --build               # Linux VM or Docker Desktop
python scripts/backfill_prices.py       # regenerate seed price history
```

## Git

- `main` protected, PR-only. `develop` is the integration branch.
- Branches: `feature/<initials>-<short-name>`. Merge to `develop` daily, end of day.
- Conventional commits: `feat|fix|test|docs|refactor|chore(scope): summary`
- PRs under ~400 lines. Rebase on `develop` before opening.

## Never

- Add JPA, Hibernate or Spring Data "just for this one entity"
- Use `double` or `float` anywhere near a financial calculation
- Return a persistence type or a raw row map from a controller
- Write a query that isn't scoped to the authenticated user
- Concatenate user input into SQL
- Edit an applied migration, another developer's directory, or `pom.xml` on a feature branch
- Commit a `.env`, key, token, client secret or password
- Make the demo path depend on a live third-party call succeeding
- Hard-code an FX rate, including in a test fixture that pretends to be production config
- Mark work complete without running the build
- Invent a domain rule that isn't in `/docs/REFERENCE_DESIGN.md` §3 or `/docs/PLAN.md` §2 — ask instead
