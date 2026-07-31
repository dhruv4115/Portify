# Day 3 — Dev A — Transactions, valuation, and the performance series · **MVP DAY**

## You have no prior context. Read this whole file before doing anything.

You are **Dev A** on a three-person team building **Protify**, a Portfolio Management REST API
(Java 21, Spring Boot 3.5.16, MySQL 8.4, `NamedParameterJdbcTemplate` — **no JPA**) plus a
React frontend. Base package `com.protify.portfolio`.

**You own:** all POMs, `portfolio-common` (frozen), `portfolio-core`, Flyway `V1`–`V9`.

**Already merged:** `MoneyUtils`, enums, exceptions, the `MarketDataProvider` /
`FxRateProvider` ports, `V1`/`V2`, `BaseRepository`, **`ProjectionEngine`** (the pure fold),
`PortfolioRepository/Service`, `InstrumentRepository`, `TransactionRepository`,
`HoldingRepository`. Dev B has caching market-data and FX services with fallbacks. Dev C has
the portfolio and instrument controllers, and the API contract is frozen.

> ## Today is the MVP gate
> By tonight the customer's four verbs — browse, view performance, add, remove — work end to
> end in a browser, across three currencies. You carry the heaviest load. If it is going to
> fail, it will be visible by lunchtime; that is when `/docs/PLAN.md` §9 gets executed.

**Read first:** `/docs/PLAN.md` §5 and §9 · `/docs/DECISIONS/0010-performance-series-in-java.md`
· `0011-multi-currency-valuation.md` · `/docs/REFERENCE_DESIGN.md` §3 · `/docs/TEST_PLAN.md` §4.

---

## D3-A1 · `TransactionService.record()` — 2.0 h · 🔴

`portfolio-core/…/core/transaction/TransactionService.java`

The whole thing in **one `@Transactional`**, in this order:

1. `portfolioRepository.lockForUpdate(userId, portfolioId)` — `SELECT … FOR UPDATE`. No row → `NotFoundException` (404).
2. `instrumentRepository.findBySymbol(symbol)` — not found → `NotFoundException` (404). **Before any write.**
3. Currency check: `txn.currency` must equal `instrument.currency`, else `CurrencyMismatchException` (422). Note the portfolio *may* mix currencies — the constraint is per instrument, not per portfolio.
4. Load the full history, append the new transaction, run `ProjectionEngine` in **native** mode. A `SELL` exceeding the holding throws `InsufficientQuantityException` (422) **here**, before the insert commits.
5. Insert the `txn` row (`BaseRepository` `KeyHolder`).
6. Upsert the projection.
7. If cash went negative, add a **warning** — do not reject. REFERENCE_DESIGN §3 and `/docs/PLAN.md` §2.7.

Return a result carrying the new id and `warnings`. `warnings` is an empty list, never `null`.

**Tests — `TransactionServiceTest`, 10 cases:** happy BUY; happy SELL; SELL exceeding holding →
422 and **nothing written**; unknown symbol → 404 and **nothing written**; currency mismatch →
422; BUY over cash → 201 with a warning; quantity 0 → rejected; future `executedAt` → rejected;
fractional quantity accepted at 6 dp; a projection failure rolls back the `txn` insert
(`TransactionServiceIT` — assert the row count is unchanged).

---

## D3-A2 · `TransactionService.delete()` — 1.0 h · 🔴

Same file. Delete, then **full rebuild** of that portfolio's projection from the remaining
`txn` rows — same lock, same DB transaction, same `ProjectionEngine`.

**There is no inverse-operation code path.** Weighted-average cost has no exact inverse, which
is the whole reason for the transaction-centric model (ADR-0002). Delete → `deleteAllForPortfolio`
→ replay → upsert.

If the rebuild fails because a later `SELL` no longer has cover, throw 422 and let the
transaction roll back. The history must stay internally consistent.

**Test — `MidHistoryDeleteIT`, the assessor's favourite:** five transactions (BUY 10, BUY 5,
SELL 3, BUY 2, SELL 4); delete **the first**; assert the resulting `holding` row is identical —
quantity, `avg_cost` and `realised_pnl` — to inserting the remaining four into a fresh empty
portfolio. Plus: deleting a BUY that leaves a later SELL uncovered → 422 and full rollback.

---

## D3-A3 · `ValuationService` — 2.0 h · 🔴

`portfolio-core/…/core/valuation/ValuationService.java`

Run **the same `ProjectionEngine`** a second time, in base-currency mode: target =
`portfolio.base_currency`, FX resolved **at each transaction's date**. Nothing is persisted.

```
marketValue   = Σ quantity × price(native, asOf) × fx(native → base, asOf)
costBasis     = base-currency projection run, FX at each transaction's date
unrealisedPnl = marketValue − costBasis
unrealisedPnlPct = MoneyUtils.pctChange(costBasis, marketValue)   // null when costBasis is 0
totalValue    = marketValue + cashBalance
```

**Market value uses today's rate; cost basis uses trade-date rates.** The difference between
them is the currency P&L, and reporting it correctly is the point of ADR-0011. Converting cost
basis at today's rate would silently erase most of the return on a mostly-foreign portfolio.

Call Dev B's services through the **ports in `portfolio-common`**. `portfolio-core` cannot
import `portfolio-platform` — if you find yourself wanting to, the interface belongs in `common`
and `common` is frozen, so raise it in the channel.

Surface `priceAsOf` and `rateAsOf` on the result. Dev C renders them as `dataQuality`.

**A missing price must not blank the portfolio:** one unpriceable instrument returns `null`
market value for that holding with `stale: true`, and the endpoint still returns 200.

**Tests — `ValuationServiceTest`, 12 cases:** single-currency portfolio correct;
**AAPL(USD) + RELIANCE(INR) + SHEL(GBP) valued in INR correct to 4 dp**; empty portfolio → all
zeros and `unrealisedPnlPct` **`null`**; zero cost basis → `null` percentage; missing price →
falls back to the most recent prior price and reports the older `priceAsOf`; no price anywhere →
`null` market value, 200, `stale: true`; never forward-fills from a *future* price; `asOf`
before the first transaction → 400; cash-only portfolio; base currency changed → all values
change, no stored row changes.

---

## D3-A4 · `PerformanceService` — 1.5 h · 🔴

`portfolio-core/…/core/valuation/PerformanceService.java`

**Three flat queries, then one in-memory fold.** Not SQL. Read
`/docs/DECISIONS/0010-performance-series-in-java.md` before writing a line — this decision is
what makes a 1.5-hour estimate realistic for the hardest code in the project.

```java
List<Txn> txns = txnRepo.findByPortfolioOrderByExecutedAt(id);
Map<Long, NavigableMap<LocalDate, BigDecimal>> prices = priceRepo.findRange(ids, from, to);
NavigableMap<LocalDate, Map<CurrencyCode, BigDecimal>> fx = fxRepo.findRange(from, to);
```

Walk the date range once, advancing a transaction cursor, maintaining the running position map,
valuing at each date. `NavigableMap.floorEntry(date)` gives "most recent on or before" in
O(log n) — the forward-fill that would have been a `LATERAL` subquery is one method call.

**Rules the fold must encode:**
- **Forward-fill only.** Carry the last known value forward. Never interpolate. **Never look ahead** — a future price used for a past date invents history and will pass a naive test.
- Filled points are flagged `filled: true`.
- Dates before the first transaction are **omitted**, not zero-filled.
- `summary.netContributions` separates deposits from market movement, so `percentChange` is not flattered by the user's own money.

**Tests — `PerformanceServiceTest`, 8 cases:** one point per day; weekend forward-filled and
flagged; a transaction on a market holiday lands correctly; dates before the first transaction
omitted; `from > to` → 400; range over 5 years → 400; three-currency portfolio correct;
90 days in under 300 ms.

---

## Rules

- **`portfolio-common` is frozen.** Changes need team agreement.
- `portfolio-core` must not import `portfolio-platform`. Use the ports.
- No `double`, no `float`. `compareTo`, never `equals`. All rounding via `MoneyUtils`.
- No JPA, no Hibernate, no Spring Data. No `JdbcTemplate` outside a `*Repository`.
- **The projection is written in the same DB transaction as the `txn` row.** Never two transactions, never async.
- **Invent no domain rule.** If REFERENCE_DESIGN §3 and `/docs/PLAN.md` §2 do not cover it, ask.

## Do not touch

`portfolio-platform/**` (Dev B) · `portfolio-api/**` (Dev C) · migrations `V10`+ · frontend.

---

## If you are behind at 14:00 — execute `/docs/PLAN.md` §9

Cut in this order, and say so at stand-up rather than working late silently:
1. Allocation (it is tomorrow's task anyway — do not start it)
2. `summary.netContributions`
3. Cost basis at **trade-date** FX → use the latest rate instead, and document the limitation loudly
4. `interval=WEEKLY/MONTHLY` → `DAILY` only

**Never cut the performance series itself.** It is customer priority 2. Cut Day 4 and Day 5
work instead.

---

## Done when

- [ ] BUY, SELL, DIVIDEND, DEPOSIT, WITHDRAWAL, FEE all record correctly
- [ ] SELL over holding → 422, **nothing written**
- [ ] Unknown symbol → 404, **nothing written**
- [ ] BUY over cash → succeeds with a warning
- [ ] `MidHistoryDeleteIT` green — deleting the first of five equals a fresh replay of the other four
- [ ] Three-currency portfolio values correctly into INR
- [ ] Zero cost basis → `unrealisedPnlPct` is `null`
- [ ] Performance series: one point per day, weekends filled and flagged, 90 days under 300 ms
- [ ] `mvn clean verify` green; merged to `develop` by 17:30

## Hand-off

**Merge `TransactionService` before `ValuationService`** — Dev C's `TransactionController` is
the customer's "add" and "remove", and it is blocked until yours lands.

Tomorrow: the remaining edge cases from `/docs/TEST_PLAN.md` §4, the concurrency test, the
JaCoCo gate at 70/60, and allocation. Tomorrow is also when you deliberately **remove** the
`AND user_id = :userId` clause from one repository and confirm Dev C's cross-user test goes
red — a cross-user test that passes with the guard removed is worthless, and the screenshot
goes in the presentation.
