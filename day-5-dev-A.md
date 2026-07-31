# Day 5 — Dev A — Analytics and performance (buffer day)

## You have no prior context. Read this whole file before doing anything.

You are **Dev A** on a three-person team building **Protify**, a Portfolio Management REST API
(Java 21, Spring Boot 3.5.16, MySQL 8.4, `NamedParameterJdbcTemplate` — **no JPA**) plus a
React frontend. Base package `com.protify.portfolio`.

**You own:** all POMs, `portfolio-common` (frozen), `portfolio-core`, Flyway `V1`–`V9`.

**Status:** MVP shipped Day 3. Edge cases, concurrency, the coverage gate and allocation
shipped Day 4. Docker, compose and a real green Jenkins pipeline shipped Day 4.

---

## ⚠️ Read this before anything else

**Day 5 is the schedule buffer. Every task below is MoSCoW *Could*.**

Run this checklist first, and be honest:

- [ ] Is `develop` green — `mvn clean verify` in full, integration tests included?
- [ ] Are all ten edge cases in `/docs/TEST_PLAN.md` §4 passing?
- [ ] Is `CrossUserAccessIT` covering every endpoint and **verified red** with a guard removed?
- [ ] Does `docker compose up` work from a clean volume?
- [ ] Did a real push produce a green Jenkins run?
- [ ] Does the full customer loop work in a browser?

**If any box is unticked, close it today and skip everything below.** A smaller product that
works scores better than a larger one that does not — that is `/docs/RISKS.md` R2, the most
common way projects like this fail, and today is when it happens.

---

## D5-A1 · `AnalyticsService` — 3.0 h · 🟢

`portfolio-core/…/core/valuation/AnalyticsService.java`

Built on the performance series you already have:

| Metric | Definition |
|---|---|
| **Time-weighted return** | chain-link the sub-period returns between cash flows — this is the one that is *not* just `(end−start)/start` |
| **Annualised return** | `(1 + TWR)^(365/days) − 1` |
| **Max drawdown** | largest peak-to-trough decline over the period |
| **Best / worst day** | largest single-day gain and loss, with dates |
| **Volatility** | standard deviation of daily returns, annualised — optional |

**TWR is the one worth getting right.** A user who deposits ₹100,000 on a good day has not
earned a return, and a naive `(end − start) / start` says they have. Chain-linking sub-period
returns between cash flows is the correct treatment and it is what separates this from a
spreadsheet. It also uses `netContributions`, which the series already tracks.

Edge cases: a period with no transactions; a period with a deposit on day 1; a portfolio that
went to zero and came back; a single-day period (return is 0, not undefined).

**Tests — `AnalyticsServiceTest`:** **build the fixture in a spreadsheet by hand** and assert
to 4 decimal places. This is the one place where "the code agrees with itself" is easiest to
mistake for correctness, because every number looks plausible.

---

## D5-A2 · `portfolio_valuation_daily` materialisation — 2.0 h · 🟢

`portfolio-core/…/core/valuation/ValuationSnapshotService.java`

The table already exists in `V1__baseline.sql` and has been unused all week. Use it as a
**cache over** the fold, never as the source of truth:

- A nightly job writes one row per portfolio per completed day.
- `PerformanceService` reads snapshots for completed days and folds live only for today.
- **Any write or delete invalidates snapshots from that transaction's date onward.** A
  mid-history delete changes every subsequent day, so a stale snapshot is a wrong chart.
- If a snapshot is missing, compute it — never return a gap.

The relationship matters and it is worth stating in the presentation: the fold is the source of
truth, snapshots are a derived cache, and the cache is invalidated by the same event that makes
it wrong. Same relationship as `txn` and `holding`.

**Test — `ValuationSnapshotIT`:** snapshot values equal live-fold values exactly; a mid-history
delete invalidates the right range; a 365-day series drops below 100 ms.

---

## D5-A3 · Performance pass — 1.5 h · 🟢

`V3__perf_indexes.sql` (your range).

Profile first, then index. Likely candidates: `price_history (instrument_id, price_date)`
already covered by the unique key; `fx_rate (rate_date)`; `txn (portfolio_id, executed_at)`
already in the baseline.

Hunt N+1s — the most likely one is a per-instrument price lookup inside a holdings loop. Batch
it into a single `IN` query.

**Test — `PerformanceBudgetIT`:** with 500 transactions, 20 instruments and 365 days, no
endpoint exceeds 500 ms.

---

## Rules

- **`portfolio-common` is frozen.** `portfolio-core` must not import `portfolio-platform`.
- No `double`, no `float`. `compareTo`, never `equals`. Rounding through `MoneyUtils`.
- No JPA, no Hibernate, no Spring Data. No `JdbcTemplate` outside a `*Repository`.
- Migrations only in `V1`–`V9`, only new ones.
- **Do not lower the coverage gate** to accommodate new code.
- **Nothing merges after 17:00 today.** Tomorrow is freeze and rehearsal; a late merge tonight is a bug discovered during the demo.

## Do not touch

`portfolio-platform/**` (Dev B) · `portfolio-api/**` (Dev C) · migrations `V10`+ · frontend.

---

## Done when

- [ ] Either: all three tasks above shipped with tests — **or** Days 1–4 gaps closed and this list consciously skipped
- [ ] TWR verified against a hand-built spreadsheet to 4 dp
- [ ] Snapshots equal live-fold values exactly, and invalidate correctly on a mid-history delete
- [ ] No endpoint over 500 ms with 500 transactions
- [ ] `mvn clean verify` green; **merged by 17:00**

## Hand-off

Tomorrow you build `V4__demo_seed.sql` — a deterministic demo portfolio with three currencies
and ~25 transactions over 18 months — and `ProjectionRebuildConsistencyIT`, which rebuilds
**every** projection from `txn` and asserts zero diff against the live tables. That test is the
proof that the transaction-centric model actually holds, and it is worth showing on screen.

**Tomorrow is feature freeze at midday.** Anything not merged by then is cut. Decide tonight
what you are not going to finish, and say so at stand-up rather than discovering it at 14:00.
