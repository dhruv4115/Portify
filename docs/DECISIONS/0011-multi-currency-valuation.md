# ADR-0011 · Native currency stored, base currency presented

**Status:** Accepted · **Date:** Day 0 · **Owner:** Tech lead · **Supersedes:** REFERENCE_DESIGN §3 row "Mixed currencies in one portfolio"

## Context

`REFERENCE_DESIGN` §3 states: *"Mixed currencies in one portfolio | rejected in v1; FX is a
documented limitation."* The customer has since reversed this explicitly and in detail. An
investor may hold AAPL in USD, RELIANCE in INR and Shell in GBP simultaneously, and must see
the whole portfolio valued in a base currency of their choosing at live FX rates. Changing
that base currency must alter no underlying data — it is a presentation change only.

This is the largest scope increase over the frozen design, roughly 12 developer-hours, and it
touches every read path. It needs a decision recorded, not just an implementation.

The design question is narrow and consequential: **where does the conversion happen?**

Three places it could go:

1. **At write time** — convert to base currency and store that. Simple reads.
2. **At read time from stored aggregates** — store native, convert the `holding` row on the way out.
3. **At read time by replaying history** — store native, recompute base-currency figures from `txn` with FX at each transaction's date.

Option 1 is immediately disqualified by the customer's own constraint: if base-currency values
are stored, changing the base currency means rewriting every row. It also destroys
information — the native price is the fact; the converted one is a derived view at a rate that
was true once.

Option 2 works for **market value** (quantity × today's price × today's rate) but breaks on
**cost basis**. `holding.avg_cost` is a native-currency average with no record of what the FX
rate was on each purchase. Converting it at today's rate reports what the position *would*
have cost if bought today — which is not what it cost, and it silently erases the currency
component of P&L. For a portfolio 90 % denominated outside its base currency, that component
is most of the return.

## Decision

**Store native. Present base. Derive base-currency cost basis by replaying the transaction
history with FX resolved at each transaction's date.** Option 3.

| Concept | Where | Mutable |
|---|---|---|
| **Native / trading currency** | `instrument.currency`, `txn.currency`, `txn.price`, `holding.avg_cost` | never |
| **Base currency** | `portfolio.base_currency` | user-editable via `PATCH /portfolios/{id}` |
| **Presentation currency** | `?currency=` query override | per request |

Mechanics:

1. `holding.avg_cost` stays in the instrument's native currency. **No stored column is ever
   denominated in a base currency.**
2. `ValuationService` runs the **same `ProjectionEngine`** a second time with a
   `ProjectionContext` carrying the target currency and an FX lookup
   `(Currency, LocalDate) → BigDecimal`. This is why ADR-0002 and ADR-0010 come first — this
   decision is only cheap because the engine is already a pure, reparameterisable fold.
3. Market value uses **today's** rate; cost basis uses the rate at **each transaction's date**.
   The difference between them is the currency P&L, and it is correct rather than hidden.
4. FX rates are stored against a **USD pivot** — one row per currency per day. A cross rate is
   `rate(USD→quote) ÷ rate(USD→base)`. Storing pairs directly would be O(n²) rows for nothing.
5. Rate on a date with no row: most recent **on or before** it. Never a future rate.
6. `fx_rate` is seeded with two years of daily history (`V12`), so FX never depends on a live call.
7. Scope is deliberately bounded: **four currencies** — USD, EUR, GBP, INR — and daily rates.
8. **Per-instrument** currency mismatch is still rejected (`422 /errors/currency-mismatch`). A
   transaction in AAPL must be in USD. It is the *portfolio* that may mix, not the trade.

`BaseCurrencyImmutabilityIT` snapshots `txn` and `holding`, changes the base currency, and
asserts zero diff. That test is the decision.

## Consequences

**Good**

- Changing base currency is provably safe — one column on one row.
- Cost basis is economically correct: P&L contains both the market move and the currency move,
  separately attributable.
- **An FX bug cannot corrupt data.** Conversion happens in one read-time layer, so the worst
  case is a wrong display, recoverable by fixing code. Had we stored converted values, a bad
  rate would be permanent.
- `?currency=` presentation override costs nothing — it is already a parameter.
- Native prices remain comparable to any external source, which makes debugging possible.
- Cleanly extends: a fifth currency is a config entry plus seed rows.

**Bad, and accepted**

- **Cost basis requires replaying transaction history on every valuation read.** At our scale
  (hundreds of transactions) this is microseconds, and Day 5 snapshots memoise it. At a
  million transactions it would be the wrong design.
- FX lookups per transaction per read. Mitigated by loading the whole date range into a
  `NavigableMap` once — the same structure ADR-0010 already builds.
- Daily rates only. An intraday trade is valued at that day's closing rate, so cost basis is
  approximate at the intraday level. Acceptable and documented; the alternative needs
  intraday FX nobody offers free.
- Two rates in play (trade-date and today) is genuinely harder to explain to a user than one.
  Mitigated by exposing `fxRateApplied` on every transaction so the number is inspectable.
- ~12 developer-hours across three developers on a six-day project. This is the cost, and it
  was funded by cutting the quantum optimiser (ADR-0009).

**Known limitation, stated rather than hidden:** we do not model FX hedging, forward
contracts, or multi-currency cash balances. Cash is held in the portfolio's base currency
only. A SELL in USD credits base-currency cash at that day's rate. Real multi-currency cash
accounts are a genuine follow-up.

## Alternatives considered

| Alternative | Why not |
|---|---|
| **Reject mixed currencies (REFERENCE_DESIGN §3 as written)** | Simplest, and it is the fallback if this fails (`/docs/PLAN.md` §9 rung 6). Rejected because the customer asked for the opposite, explicitly, with a worked example |
| **Store base-currency values at write time** | Fast reads, and it breaks the customer's stated constraint immediately: changing base currency would rewrite every row |
| **Convert `holding.avg_cost` at today's rate (option 2)** | Half the work, and it silently misstates P&L by erasing the currency component. For a mostly-foreign portfolio it is wrong about most of the return. Retained only as descope rung 5, and it would be documented loudly as a limitation |
| **Store `fx_rate_to_base` on each `txn` row** | Makes cost basis a stored fact. Directly violates the requirement — the stored rate is tied to a base currency that the user can change, so the row would need rewriting on every base-currency change |
| **A separate currency-conversion microservice** | A `Map` lookup behind a network hop |

## Revisit when

- Multi-currency **cash** is needed — a real gap, and the most likely first follow-up.
- Intraday FX matters, i.e. the product is used for actual trading rather than reporting.
- Transaction volume makes read-time replay expensive. Snapshots first; storing base-currency
  aggregates keyed by base currency second.
- A currency outside the ECB reference set is required, at which point Frankfurter is no longer
  sufficient and the `FxRateProvider` port earns its keep (ADR-0008).
