# Day 6 — Dev C — Rehearsal, slides, final documentation sweep

## You have no prior context. Read this whole file before doing anything.

You are **Dev C** on a three-person team building **Protify**, a Portfolio Management REST API
(Java 21, Spring Boot 3.5.16, MySQL 8.4, `NamedParameterJdbcTemplate` — **no JPA**) plus a
React frontend. Base package `com.protify.portfolio`.

**You own:** `api/`, `graphql/`, GitHub Actions, Flyway `V20`–`V29`, most frontend work — and
today, the presentation.

**Status:** MVP Day 3. Cross-user proof, base-currency switching and the allocation pie Day 4.
GraphQL, analytics and insights Day 5, if Day 5 was not spent on overflow.

> ## 🔒 Feature freeze at midday
> Nothing merges after 13:00 except a fix for a demo-blocking bug. **You are the person who
> enforces this** — you are running the demo, so you decide what is worth the risk.

---

## D6-C1 · Rehearse twice, timed — 2.0 h · 🔴

Run `/docs/DEMO_SCRIPT.md` end to end **twice**, timed, from a cold start
(`docker compose down -v && docker compose up`). Under 10 minutes.

**The running order — customer priorities first, engineering second:**

1. **Sign in with Google** (30 s) — real account, real token. Do this *before* Dev B's offline segment; Google JWKS is the one dependency with no offline story.
2. **Browse** (90 s) — portfolio list, then detail. AAPL in USD, RELIANCE in INR, Shell in GBP, everything totalled in INR. `avgCost` stays native, totals are base. *This is priority 1.*
3. **View performance** (90 s) — 18 months of chart. Hover a point. Note that weekends are forward-filled and flagged. *Priority 2.*
4. **Add** (60 s) — type-ahead a symbol, submit, watch holdings and the chart both move. *Priority 3.*
5. **Remove** (60 s) — delete a transaction from the **middle** of the history, and say what just happened: the entire projection was rebuilt from the ledger, in one database transaction. *Priority 4, and the best 60 seconds of engineering in the demo.*
6. **The currency switcher** (30 s) — INR → USD. Every number re-expresses; no stored row changes. Ten seconds of dropdown for the whole multi-currency architecture.
7. **A deliberate failure** (45 s) — try to sell more than you hold. Show the clean 422
   ProblemDetail with its correlation ID. **Most teams only demo the happy path.** Showing that
   the unhappy path is designed is what stands out.
8. **Cross-user** (45 s) — sign in as the second demo user, request the first user's portfolio
   id, get a **404**. Say why it is 404 and not 403: a 403 confirms the row exists.
9. **Offline** (30 s) — Dev B's segment. Stale badge, still working.
10. **Engineering** (90 s) — Jenkins green run, `docker compose ps`, the coverage report, and
    the GraphQL dashboard query returning in one round trip what REST needs four calls for.

**Time it properly.** Two rehearsals means the second is smooth; one means you discover the
rough edges live. Agree who says what — three people talking over each other reads as
disorganised regardless of the product.

**Have a fallback for every live step.** If the network misbehaves, know which screenshot to
show and say plainly that it is a screenshot.

---

## D6-C2 · Slides — 2.0 h · 🔴

Ten slides, no more.

| # | Slide | Content |
|---|---|---|
| 1 | What we built | One sentence, one screenshot |
| 2 | The customer's four priorities | Browse → performance → add → remove, and that we built them in that order |
| 3 | Architecture | The Mermaid component diagram from `/docs/ARCHITECTURE.md` §1 |
| 4 | Transaction-centric model | `txn` is truth, `holding` is a projection, and **that is why delete works** |
| 5 | Multi-currency | Native stored, base presented. Market value at today's rate, cost basis at trade-date rates |
| 6 | Offline by design | The fallback chain. "The demo does not need the internet, and here is why" |
| 7 | Security | Google OAuth2 resource server; `WHERE user_id` on every query; **404 not 403**; the cross-user test going red with the guard removed |
| 8 | Engineering | Modules, the compiler-enforced boundary, 70/60 coverage, Testcontainers, Jenkins |
| 9 | **What we cut and why** | ⭐ |
| 10 | What we would do next | Multi-currency cash, corporate actions, FIFO lots, extraction seam 1 |

**Slide 9 is the one that earns marks.** Four cuts, each with a one-line reason and an ADR
reference:

- **Quantum optimiser** — at simulable sizes, QAOA is slower and approximate where brute force is instant and exact. It would demonstrate a Qiskit import, not a benefit (ADR-0009). The ~2 days funded the multi-currency work the customer actually asked for.
- **FIFO lots** — weighted average matches the schema and composes with multi-currency; FIFO is a `txn_lot` table and a strategy parameter the engine already accepts (ADR-0005).
- **Own JWT + refresh tokens** — Google's token is already signed, short-lived and validated by Spring. Writing our own gets a worse version of a solved problem (ADR-0004).
- **Microservices** — the core write must be atomic; splitting it turns one `@Transactional` into a saga. Seams are documented with costs and triggers (ADR-0003).

Frame it as judgement, not as shortfall: *"we decided these four things were not worth the
days, here is the reasoning, and here is what we built instead."*

---

## D6-C3 · Documentation sweep — 1.0 h

- [ ] Every ADR reflects what was **actually built**. If something changed, **supersede the ADR — do not edit the decided one.** The record of a decision that turned out differently is worth more than a tidy file.
- [ ] `/docs/API_CONTRACT.md` matches the shipped API, field for field
- [ ] `/docs/PLAN.md` §9 records which descope rungs were actually used, if any
- [ ] `/docs/CUSTOMER_QUESTIONS.md` — move anything the instructor answered into the answered table
- [ ] `/README.md` quickstart works from a clean clone
- [ ] `/CLAUDE.md` "Current day" placeholder tidied

---

## Rules

- **Feature freeze at 13:00.** You enforce it.
- `common/` is frozen. **You may not edit `pom.xml`.** Migrations only in `V20`–`V29`.
- No `double`, no `float`. **Another user's resource is 404, never 403.**
- **Do not refactor anything today.** However tempting. A green build is worth more than clean code you cannot re-verify.

## Do not touch

`instrument/`, `portfolio/`, `transaction/`, `holding/`, `valuation/`, `support/` (Dev A) · `config/`, `security/`, `user/`, `marketdata/`, `fx/`, `insights/` (Dev B) · `common/` ·
any POM · migrations `V1`–`V19`.

---

## Done when

- [ ] Demo rehearsed **twice**, timed, under 10 minutes, from a cold start
- [ ] Everyone knows their segment; nobody talks over anyone
- [ ] A fallback exists for every live step
- [ ] Ten slides, with **"what we cut and why"** present and argued
- [ ] The cross-user screenshot from Day 4 is in the deck
- [ ] Every ADR matches what was built
- [ ] `/docs/CUSTOMER_QUESTIONS.md` updated
- [ ] README verified from a clean clone

---

## One last thing

If something is broken at 13:00 and cannot be fixed safely, **do not demo it and do not hide
it.** Say what is not finished and why, on slide 9, next to the four things you cut on purpose.

A team that knows exactly what it did not build, and can say why, reads as more senior than a
team demoing four half-features and hoping nobody clicks the wrong button.
