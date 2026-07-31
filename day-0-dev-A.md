# Day 0 — Dev A — Build skeleton, database, git

## You have no prior context. Read this whole file before doing anything.

You are **Dev A** on a three-person team building **Protify**, a Portfolio Management REST API
(Java 21, Spring Boot 3.5.16, MySQL 8.4) plus a React frontend. Users sign in with Google,
browse a portfolio, view its performance on a chart, and add and remove transactions.

Today is **Day 0: setup only. You write no application code.** The day succeeds when all three
developers can run the same green build against their own MySQL.

**You own:** the parent POM and all module POMs, `portfolio-common`, `portfolio-core`, and
Flyway migrations `V1`–`V9`. You are also the team's build owner — nobody else edits a
`pom.xml`.

**Read before starting, in this order:**
1. `/CLAUDE.md` — the rules. Non-negotiable.
2. `/docs/PLAN.md` §1, §2, §3, §5, and the Day 0 table in §6.
3. `/docs/REFERENCE_DESIGN.md` §1 — the schema you will implement tomorrow.
4. `/docs/DECISIONS/0001-jdbctemplate-over-jpa.md` and `0007-flyway-number-ranges.md`.

---

## Task D0-A1 · Maven skeleton — 2.0 h · 🔴 critical path

Create a multi-module Maven build. **Nothing but structure — no application classes.**

```
pom.xml                      protify-parent, packaging pom, owns ALL versions
portfolio-common/pom.xml     ports, MoneyUtils, errors, enums
portfolio-db/pom.xml         Flyway migrations only
portfolio-core/pom.xml       domain: instrument portfolio transaction holding valuation
portfolio-platform/pom.xml   config security user marketdata fx
portfolio-api/pom.xml        controllers, graphql, PortfolioApplication — the runnable app
```

Base package `com.protify.portfolio`.

**Dependency graph — enforce it in the POMs, this is the whole point:**

| Module | Depends on |
|---|---|
| `portfolio-common` | nothing of ours |
| `portfolio-db` | nothing of ours |
| `portfolio-core` | `common`; `db` at **test** scope |
| `portfolio-platform` | `common`; `db` at **test** scope |
| `portfolio-api` | `common`, `core`, `platform`, `db` |

**`portfolio-core` must NOT depend on `portfolio-platform`.** That is deliberate: core reaches
market data through interfaces in `common`, so a developer who tries to call an HTTP client
from the valuation engine gets a compile error. Do not "fix" this later by adding the
dependency — if something seems to need it, the interface belongs in `common`.

Parent POM contents: Java 21, `spring-boot-dependencies` 3.5.16 imported in
`dependencyManagement`, and pinned versions for `mysql-connector-j`, `flyway-core`,
`flyway-mysql`, `springdoc-openapi-starter-webmvc-ui` 2.8.x, `caffeine`, `testcontainers-bom`,
`assertj`, `mockito`, `jacoco-maven-plugin`, `maven-surefire-plugin`, `maven-failsafe-plugin`.

Configure failsafe so `*IT` classes run in `verify` and surefire so they do **not** run in
`test`. Add a `-DskipITs` escape hatch.

**Acceptance:** `mvn clean verify` green on empty modules. `mvn dependency:tree -pl
portfolio-core` shows no `portfolio-platform`.

---

## Task D0-A2 · Local MySQL 8.4 — 1.0 h · 🔴 critical path

Install or verify MySQL 8.4 natively. Create two schemas and a dedicated user:

```sql
CREATE DATABASE portfolio      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE portfolio_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'protify'@'localhost' IDENTIFIED BY '<local-only-password>';
GRANT ALL ON portfolio.* TO 'protify'@'localhost';
GRANT ALL ON portfolio_test.* TO 'protify'@'localhost';
```

The JDBC URL **must** carry `?serverTimezone=UTC`. Without it `DATETIME` values shift silently
between the JVM and the database, and every date assertion becomes flaky in a way that looks
like a logic bug. This is `/docs/RISKS.md` R13 and it costs half a day if you skip it.

Verify `SELECT VERSION()` returns 8.4 or later — `CHECK` constraints need 8.0.16+ and the
baseline schema uses them.

Write `/docs/DEV_SETUP.md` with the exact steps, and walk Devs B and C through it. **The task
is done when all three machines are working, not when yours is.**

---

## Task D0-A3 · Git and repository hygiene — 1.0 h

- Protect `main`: PR-only, no direct pushes, one approving review.
- Cut `develop` from `main`. It is the integration branch.
- Branch convention `feature/<initials>-<short-name>`.
- Add `.github/pull_request_template.md` containing the checklist from `/docs/DEFINITION_OF_DONE.md`.
- `.gitignore` must cover: `target/`, `.env`, `*.pem`, `*.key`, `application-local.yml`, `.idea/`, `node_modules/`, `__pycache__/`, `.venv/`.

**Keep `docs` in `.gitignore`** — the team has agreed the planning docs stay local and are not
pushed. `/CLAUDE.md` at the repository root **is** tracked; do not ignore it.

**Acceptance:** a direct push to `main` is rejected.

---

## Task D0-A4 · Agree the deviations — 1.0 h

Walk Devs B and C through `/docs/PLAN.md` §2 and §5. This is a conversation, not a document.

**§2 — five things that differ from the reference design:**
1. **Mixed currencies are now supported.** §3 of the reference design said they were rejected; the customer reversed it. This is the largest change and it touches all three of you.
2. `AssetType` gains `MUTUAL_FUND` and `TREASURY`.
3. `PriceSource` gains `YAHOO`; a new `FxSource` enum appears.
4. Testcontainers and Jenkins are real, not best-effort — the environment is better than the brief assumed.
5. The performance series is computed in Java, not SQL.

**§5 — the design everything rests on:** `ProjectionEngine` is a pure function
`(List<Txn>, ProjectionContext) → ProjectionResult`. It runs twice — once in native currency
(persisted to `holding`) and once in base currency (used for valuation, never persisted).
That single idea is what makes multi-currency cheap, makes mid-history delete correct, and
makes the performance series testable.

**Done when** both other developers can restate, without looking: why `core` cannot import
`platform`, and why holdings are recomputed rather than updated in place.

---

## Rules you must not break

- **No application code today.** No `@Service`, no `@Entity`-shaped class, no SQL beyond schema creation.
- **No JPA, no Hibernate, no Spring Data** anywhere in any POM. Not now, not later.
- Money will be `BigDecimal`, `DECIMAL(19,4)`; quantities `DECIMAL(19,6)`; FX `DECIMAL(19,8)`. Never `double` or `float`.
- No secrets in the repository. Local passwords go in `.env`, which is git-ignored; `.env.example` carries placeholders.

## What you must not touch

`portfolio-platform/**` (Dev B) · `portfolio-api/**` (Dev C) · migrations `V10`+ · the frontend
repo. If something outside your area seems to need changing, **ask in the channel**.

---

## Definition of done for today

- [ ] `mvn clean verify` green on **all three** machines
- [ ] `mvn dependency:tree -pl portfolio-core` shows no `portfolio-platform`
- [ ] MySQL 8.4 reachable from all three machines, both schemas present, `serverTimezone=UTC` proven
- [ ] `main` protected, `develop` exists, PR template in place
- [ ] `/docs/DEV_SETUP.md` written and followed by someone else successfully
- [ ] Devs B and C have read and agreed `/docs/PLAN.md` §2

## Hand-off

Post in the channel: the parent POM is on `develop`, the module graph is enforced, and
tomorrow you are delivering `MoneyUtils`, the enums, the exception hierarchy and **the two
ports** — `MarketDataProvider` and `FxRateProvider`.

Tell Dev B explicitly: **the ports land tomorrow and `portfolio-common` freezes at end of
Day 1.** Their entire Day 2 compiles against those interfaces. If they want input on the
signatures, tomorrow morning is the moment.
