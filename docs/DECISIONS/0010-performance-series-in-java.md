# ADR-0010 · The performance series is an in-memory fold, not SQL

**Status:** Accepted · **Date:** Day 0 · **Owner:** Dev A · **Supersedes:** —

## Context

`GET /portfolios/{id}/performance?from=&to=` is the customer's second priority and the hardest
query in the product. One point per calendar day, each point requiring the portfolio's
composition **as of that day**, valued at that day's prices, converted at that day's FX rates.

It combines three sparse, misaligned datasets:

- `txn` — sparse, on trade dates only, and a single transaction changes the composition for every subsequent day.
- `price_history` — one row per instrument per **trading day**, and trading days differ per exchange. NSE and NASDAQ do not share a holiday calendar.
- `fx_rate` — one row per currency per **ECB business day**, which matches neither.

Ask for 30 June and you may have no price (Sunday), no FX rate (Sunday), and a transaction
from 28 June that must already be reflected.

As SQL this is a recursive date-generating CTE, three `LEFT JOIN LATERAL` subqueries doing
"most recent row on or before this date" per instrument per day, a window function to carry
the running position, and a `GROUP BY` to aggregate. It is writable. It is also the single
easiest thing in this project to get subtly wrong: an off-by-one on a boundary date produces a
chart that looks entirely plausible and is incorrect. Debugging it means reading query plans,
and testing it means a database round trip per case.

`/docs/RISKS.md` R1 scores this the highest technical risk in the project, and it sits on the
critical path on the MVP day.

## Decision

**Three flat queries, then a single-pass merge-walk fold in Java.**

```java
List<Txn>                         txns   = txnRepo.findByPortfolioOrderByExecutedAt(id);
Map<Long, NavigableMap<LocalDate, BigDecimal>> prices = priceRepo.findRange(ids, from, to);
NavigableMap<LocalDate, Map<Currency, BigDecimal>> fx  = fxRepo.findRange(from, to);

return PerformanceFolder.walk(txns, prices, fx, from, to, baseCurrency);
```

The fold walks the date range once, advancing a transaction cursor as it goes, maintaining
the running position map, and valuing it at each date. `NavigableMap.floorEntry(date)` gives
"most recent price on or before this date" in O(log n) — the forward-fill that was a
`LATERAL` subquery becomes one method call.

Complexity is `O(days × instruments + transactions)`. For 365 days and 20 instruments that is
~7,300 lookups: microseconds.

**Rules the fold encodes explicitly:**

- Forward-fill only. Carry the last known value forward; **never interpolate, never look
  ahead.** A future price used for a past date invents history.
- A filled point is flagged `"filled": true` in the response.
- Dates before the first transaction are **omitted**, not zero-filled.
- FX is resolved at each transaction's date for cost basis, and at each valuation date for
  market value. Those are different rates and the difference is the currency P&L.

This is the same `ProjectionEngine` fold used for the holdings projection, parameterised with a
date cursor. **One engine, three call sites** (native projection, base-currency valuation,
performance series), so a bug in the position maths is a bug in all three and cannot hide in
the rarely-exercised one.

## Consequences

**Good**

- **The highest risk in the project becomes an ordinary algorithm.** Testable with hand-built
  lists — no database, no Spring, no clock, no query plan.
- Fixtures come from a spreadsheet, so "the code agrees with itself" cannot pass for correct.
- Edge cases are cheap to express. "Transaction on a market holiday", "instrument with no
  price for three days", "FX gap over Christmas" are three lines each, running in milliseconds.
- Debuggable with a breakpoint and a step, not `EXPLAIN ANALYZE`.
- Portable — no MySQL-specific window-function syntax.
- Directly enabled multi-currency (ADR-0011). Adding FX to a fold is a lookup; adding it to
  the SQL version would have meant a fourth joined series.
- The estimate drops from ~1 day to ~1.5 hours, which is what makes Day 3's MVP achievable.

**Bad, and accepted**

- **All the data comes into memory.** For 500 transactions, 20 instruments and 5 years of
  prices that is ~25k rows and a few MB. At 100× the data this becomes the wrong answer, and
  the mitigation is a bounded range — the API caps `to − from` at 5 years and returns 400
  beyond it.
- We do not use the database for what it is good at. A DBA would object, and would be right at
  a different scale.
- Three round trips instead of one. Irrelevant against a local MySQL; they are also issued
  concurrently.
- No incremental update — every call recomputes the whole series. Mitigated on Day 5 by
  `portfolio_valuation_daily` snapshots (`D5-A2`), which memoise completed days while leaving
  the fold as the source of truth.

## Alternatives considered

| Alternative | Why not |
|---|---|
| **One recursive-CTE SQL query** | The "proper" answer, and probably faster. Rejected on risk, not on merit: it is the hardest code in the project, on the critical path, on the MVP day, tested only through a database. Correctness we can prove beats performance we do not need |
| **Materialise `portfolio_valuation_daily` on write** | Fast reads, and the table already exists in the schema. Rejected as the primary mechanism because a mid-history delete invalidates every snapshot after it, and rebuilding them is the fold anyway. Adopted as a **cache over** the fold on Day 5, which is the right relationship |
| **Nightly batch precompute only** | Today's point is always missing, and the customer will add a transaction during the demo and expect the chart to move |
| **Compute in the frontend from raw transactions** | Money maths in JavaScript, on IEEE-754 doubles. Violates the first non-negotiable in `CLAUDE.md` |

## Revisit when

- A portfolio exceeds ~10,000 transactions or a range exceeds 5 years — the point where memory
  and per-call recomputation stop being free.
- Multiple concurrent users request long series and CPU becomes visible. Snapshots first, SQL
  second.
- We need cross-portfolio or firm-wide aggregation, which is genuinely a set-based problem and
  where SQL is the right tool.
