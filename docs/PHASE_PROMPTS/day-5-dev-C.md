# Day 5 — Dev C — GraphQL and frontend finishing (buffer day)

## You have no prior context. Read this whole file before doing anything.

You are **Dev C** on a three-person team building **Protify**, a Portfolio Management REST API
(Java 21, Spring Boot 3.5.16, MySQL 8.4, `NamedParameterJdbcTemplate` — **no JPA**) plus a
React frontend. Base package `com.protify.portfolio`.

**You own:** `api/` (controllers, `dto/`, `mapper/`, `error/`) and `graphql/`, GitHub
Actions, Flyway `V20`–`V29`, and most frontend work.

**Status:** MVP Day 3. Day 4 delivered `CrossUserAccessIT`, `PATCH` base-currency switching
with an immutability proof, the allocation pie and the currency switcher.

---

## ⚠️ Read this before anything else

**Day 5 is the schedule buffer. Every task below is MoSCoW *Could*.**

- [ ] Is `develop` green — `mvn clean verify` in full?
- [ ] Does the full customer loop work in a browser: browse → chart → add → remove?
- [ ] Is `CrossUserAccessIT` complete and **verified red** with a guard removed?
- [ ] Is every empty state handled — no portfolios, no holdings, a one-point chart, a zero-point chart?
- [ ] Do error toasts render from `ProblemDetail`?

**If any box is unticked, close it today and skip everything below.** `/docs/RISKS.md` R2.

**And the hard rule for today, from R2:** if GraphQL is half-built at **15:00**, revert it. A
merged half-feature is worse than an ADR explaining the deferral, and the "what we cut and why"
slide gets marks that a broken resolver does not.

---

## D5-C1 · GraphQL — 3.0 h · 🟢

`graphql/schema.graphqls` + resolvers

**The SDL is already written — `/docs/API_CONTRACT.md` §19.** Copy it; do not redesign it.

Rules that make this cheap and safe:

- **Read-only in v1.** Mutations stay on REST, where the transactional and `Location`-header semantics live.
- **Resolvers call the same services as REST.** Not the repositories — the services. An authorisation bug then cannot exist in one API and not the other, which is the whole reason this is a 3-hour task rather than a day.
- Same security context, same `userId` scoping. `portfolio(id:)` returns `null` for another user's portfolio — the GraphQL equivalent of our 404.
- Query **depth limit 6**, complexity limit 200. Without them, a nested query is a denial-of-service.
- GraphiQL enabled in the **local profile only**.
- Errors use GraphQL's `errors[]` array, not ProblemDetail — the spec mandates it. Put `correlationId` and a `classification` slug in `extensions` so failures stay traceable across both APIs.

Build the dashboard query as the justification for the feature existing at all: portfolio +
holdings + performance + allocation in **one round trip**, where REST costs four. Show that
side by side in the demo — it is the only argument for GraphQL that actually lands.

**Tests — `GraphQlQueryTest`:** the dashboard query returns all four sections; an unauthenticated
query is rejected; another user's portfolio resolves to `null` with `NOT_FOUND`; a query over
depth 6 is rejected.

---

## D5-C2 · Frontend — analytics, insights, export — 2.5 h · 🟢

- **Analytics panel** — TWR, annualised return, max drawdown, best/worst day from Dev A's `AnalyticsService`. Label TWR clearly; it is the number that needs explaining and the one that shows the product understands finance.
- **Insights card** — Dev B's endpoint. **Render the `engine` value**: an "AI-generated" or "rule-based summary" label. Honest, and it means the offline path looks deliberate rather than broken.
- **CSV export** of transactions and holdings. Half an hour, and demos well.

---

## D5-C3 · README — 1.0 h · 🟢

`/README.md` in the backend repo — **this is tracked in git, unlike `/docs/`, so for anyone
outside the team it is the only documentation that exists.**

- What it is, in three sentences
- Architecture diagram (reuse the Mermaid from `/docs/ARCHITECTURE.md` §1)
- Five-minute quickstart: clone → `.env` → `docker compose up` → sign in
- Local development without Docker: native MySQL, `mvn spring-boot:run`
- The stack table, and a link to the frontend repo

**Acceptance:** someone outside the team gets it running from the README alone. Actually ask
one of the other developers to try it, from a clean clone.

---

## Rules

- **DTOs are `record`s.** Persistence types never cross the controller boundary.
- **All errors through `GlobalExceptionHandler`** for REST; `extensions` for GraphQL.
- **No `double`, no `float`.** Money is `BigDecimal` in Java, a **string** in JSON.
- **Another user's resource is 404 in REST, `null` in GraphQL. Never 403.**
- `common/` is frozen. **You may not edit `pom.xml`** — you need `spring-boot-starter-graphql`, so ask Dev A **this morning**.
- Migrations only in `V20`–`V29`.
- **Nothing merges after 17:00 today.**

## Do not touch

`instrument/`, `portfolio/`, `transaction/`, `holding/`, `valuation/`, `support/` (Dev A) · `config/`, `security/`, `user/`, `marketdata/`, `fx/`, `insights/` (Dev B) · `common/` ·
any POM · migrations `V1`–`V19`.

---

## Done when

- [ ] Either: all three tasks shipped — **or** Days 1–4 gaps closed and this list consciously skipped
- [ ] GraphQL resolvers call **services**, not repositories
- [ ] Another user's portfolio resolves to `null`, tested
- [ ] Depth and complexity limits enforced
- [ ] The dashboard query returns four sections in one round trip
- [ ] Insights card shows the `engine` value honestly
- [ ] README gets a stranger running from a clean clone
- [ ] `mvn clean verify` green; **merged by 17:00**

## Hand-off

Tomorrow you rehearse the demo **twice**, timed, and build the slides. The most important slide
is **"what we cut and why"** — quantum optimisation (ADR-0009), FIFO lots (ADR-0005), the
own-JWT exchange (ADR-0004), microservices (ADR-0003). Cutting deliberately, with a written
argument, is a stronger signal than shipping four half-features, and that slide is where it
shows.

Also plan to **show a failure on purpose**: an invalid sell producing a clean 422 ProblemDetail
with a correlation ID. Most teams only demo the happy path; showing that the unhappy path is
designed is the thing that stands out.

**Feature freeze at midday tomorrow.** Nothing merges after 13:00 except a fix for a
demo-blocking bug.
