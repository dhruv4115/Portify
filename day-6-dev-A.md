# Day 6 — Dev A — Demo data, the rebuild proof, final build

## You have no prior context. Read this whole file before doing anything.

You are **Dev A** on a three-person team building **Protify**, a Portfolio Management REST API
(Java 21, Spring Boot 3.5.16, MySQL 8.4, `NamedParameterJdbcTemplate` — **no JPA**) plus a
React frontend. Base package `com.protify.portfolio`.

**You own:** all POMs, `portfolio-common` (frozen), `portfolio-core`, Flyway `V1`–`V9`.

**Status:** MVP shipped Day 3. Edge cases, concurrency and the coverage gate Day 4. Docker,
compose and Jenkins Day 4. Analytics and snapshots Day 5, if Day 5 was not spent on overflow.

> ## 🔒 Feature freeze at midday
> **Nothing merges after 13:00 except a fix for a demo-blocking bug.** The showcase is this
> afternoon. A late merge today is a bug discovered in front of the assessor.
>
> If something is unfinished at 13:00: **it is cut.** Say so, put it on the "what we cut"
> slide, and move on. That slide is worth more marks than the half-feature would have been.

---

## D6-A1 · `V4__demo_seed.sql` — 1.5 h · 🔴

The demo portfolio, deterministic and interesting from a cold start.

- One demo user, plus **a second user with their own portfolio** — you need that second user to demonstrate the cross-user 404 live.
- Portfolio "Growth", base currency **INR**.
- **~25 transactions over 18 months**, across at least three currencies: AAPL (USD), RELIANCE (INR), SHEL (GBP), plus an ETF and a treasury so the allocation pie has shape.
- Include a `DEPOSIT`, a `DIVIDEND`, a `SELL` that realises a profit, and a `SELL` that closes a position to zero — so the demo can show realised P&L and `includeZero`.
- Fixed dates and quantities. **No `NOW()`, no randomness.** The chart must look the same on every machine, or the rehearsal proves nothing.

**Acceptance:** a fresh database — `docker compose down -v && docker compose up` — produces a
portfolio with 18 months of chart history, a positive return, and a visible FX contribution.

**Test — `DemoSeedIT`:** the demo portfolio exists, has ≥ 3 currencies, ≥ 20 transactions, a
non-zero realised P&L, and at least one closed position.

---

## D6-A2 · `ProjectionRebuildConsistencyIT` — 1.0 h · 🔴

`portfolio-core/…/core/holding/ProjectionRebuildConsistencyIT.java`

For **every** portfolio in the database: rebuild the projection from `txn` and assert the result
is identical — quantity, `avg_cost` and `realised_pnl` — to what is stored in `holding`.

**This test is the transaction-centric model's proof of correctness** (ADR-0002). If it passes,
the projection is genuinely derived and has not drifted. If it fails, some write path bypassed
the engine, and it is much better to find that now than during the demo.

Worth putting on screen during the technical part of the presentation — it is a one-line
statement of the architecture's central claim.

---

## D6-A3 · Final clean build — 1.0 h

- `git clone` into a **fresh directory** and run `mvn clean verify`. Not your working copy — a genuinely clean clone, on a machine that has never built it if possible. Stale local state hides missing files, and this is the last chance to find one.
- Archive the JaCoCo report.
- Confirm the coverage gate is on and passing at 70/60, with 90 on the money classes.
- Confirm every migration applies to an **empty** schema.

---

## Afternoon · Support the demo

Once the freeze is in, your job is to be available, not to be coding.

**Be ready to answer, without looking anything up:**

- **Why no JPA?** Explicit SQL means the `WHERE user_id` predicate is visible in the code a reviewer reads. For a system whose main security property is per-user scoping, the control and the reviewed text are the same thing (ADR-0001).
- **Why is `holding` a projection?** Weighted-average cost has no exact inverse, so a mid-history delete cannot be computed by reversal. Replay is correct by construction (ADR-0002).
- **Why weighted average and not FIFO?** It matches the committed schema, keeps per-instrument state to three numbers, and composes with multi-currency. FIFO is a `txn_lot` table and a strategy parameter the engine already accepts (ADR-0005).
- **How does multi-currency work?** Native stored, base presented. Market value at today's rate, cost basis at trade-date rates — the difference is the currency P&L (ADR-0011).
- **Why is the performance series not SQL?** It was the highest-risk item in the project. As a fold it is testable with hand-built lists and no database, which dropped the estimate from a day to 90 minutes (ADR-0010).
- **What would you do next?** Multi-currency cash balances, corporate actions, and FIFO lots — in that order.

Have `ProjectionEngineTest` and `MidHistoryDeleteIT` open in a tab. If anyone asks how you know
the maths is right, showing 18 test cases built from a spreadsheet is a better answer than
describing them.

---

## Rules

- **Feature freeze at 13:00.** After that, demo-blocking fixes only.
- `portfolio-common` is frozen. Migrations only in `V1`–`V9`.
- No `double`, no `float`. No JPA. No `JdbcTemplate` outside a `*Repository`.
- **Do not refactor anything today.** However tempting. A green build is worth more than clean code you cannot re-verify.

## Do not touch

`portfolio-platform/**` (Dev B) · `portfolio-api/**` (Dev C) · migrations `V10`+ · frontend.

---

## Done when

- [ ] `V4__demo_seed.sql` gives an interesting portfolio from a cold start, deterministically
- [ ] A second user exists, for the live cross-user demonstration
- [ ] `ProjectionRebuildConsistencyIT` green across every portfolio
- [ ] `mvn clean verify` green from a **fresh clone**
- [ ] Coverage gate on and passing; report archived
- [ ] Every migration applies to an empty schema
- [ ] You can answer the six questions above without notes
