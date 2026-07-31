# ADR-0005 · Weighted-average cost basis, not FIFO lots

**Status:** Accepted · **Date:** Day 0 · **Owner:** Dev A · **Supersedes:** —

## Context

When you sell part of a position, which shares did you sell? The answer determines realised
P&L and remaining cost basis, and there are three common conventions:

- **Weighted average (WAC):** every share has the same cost — the running average. Sell any
  share, realised P&L is `(price − avgCost) × qty − fees`. `avgCost` is unchanged by a sale.
- **FIFO:** oldest lots sell first. Requires tracking each purchase as a separate lot with its
  own quantity, cost and date.
- **Specific identification:** the user picks which lot. Requires FIFO's machinery plus a UI.

A worked example. Buy 10 @ 100, buy 10 @ 200, sell 5 @ 250.

| Method | Realised P&L | Remaining |
|---|---|---|
| WAC | `(250 − 150) × 5 = 500` | 15 @ avg 150 |
| FIFO | `(250 − 100) × 5 = 750` | 15, as 5 @ 100 + 10 @ 200 |

Both are defensible; they differ in reported profit and, in the real world, in tax. FIFO is
what most jurisdictions require for tax reporting, so it is the "more correct" answer in a
sense that matters outside this project.

Two facts constrain the choice. First, `REFERENCE_DESIGN` §1 already defines
`holding.avg_cost DECIMAL(19,4)` and §3 already specifies weighted-average behaviour on BUY —
the schema is committed. Second, this product does no tax reporting: it shows a portfolio and
its performance.

## Decision

**Weighted-average cost basis for the MVP. `holding.avg_cost` holds the running weighted
average in the instrument's native currency, including fees.**

Rules, restating REFERENCE_DESIGN §3 so the tests have one source:

| Event | Effect on `avg_cost` |
|---|---|
| BUY | `(oldQty × oldAvg + newQty × newPrice + fees) ÷ (oldQty + newQty)` |
| SELL | **unchanged**. `realised_pnl += (price − avgCost) × qty − fees` |
| SELL to zero | row kept at quantity 0, preserving `realised_pnl`; filtered from default responses |
| DIVIDEND | no effect on `avg_cost`; increases cash |

**Fees are capitalised into cost on BUY and expensed against proceeds on SELL.** This is the
one genuinely arbitrary sub-decision here, so it is written down: a buy fee raises your cost
basis, a sell fee reduces your realised gain. It is what most retail brokers show.

The design leaves room for lots. `ProjectionEngine` takes a `ProjectionContext`; adding a
`CostBasisMethod` to it and a `txn_lot` table would introduce FIFO without changing any
caller. That is a deliberate extension point, not an accident.

## Consequences

**Good**

- One `DECIMAL(19,4)` column instead of a `txn_lot` table, its repository, its mapper and its
  lifecycle. Roughly a day saved on Day 2–3, which is the tightest part of the week.
- Matches the committed schema, so no migration churn and no re-litigating a frozen design.
- The projection stays a simple fold — the state per instrument is `(quantity, avgCost,
  realisedPnl)`, three numbers. With lots, the state is a list, and every delete-and-rebuild
  has to reconstruct lot ordering correctly.
- **Composes cleanly with multi-currency (ADR-0011).** Running the fold a second time with FX
  applied gives base-currency cost basis directly. With FIFO, each lot would additionally
  carry its own trade-date FX rate — correct, but materially more state.
- Deleting a mid-history transaction is a plain replay. With FIFO it is a replay that must
  also re-derive which lots subsequent sales consumed.

**Bad, and accepted**

- **Realised P&L will not match a broker statement** for a user who has bought at different
  prices. In the example above we report 500 where a FIFO broker reports 750. This is a real
  difference and it is disclosed in the UI, not hidden.
- Not usable for tax reporting in most jurisdictions.
- No lot-level view — a user cannot see "the shares I bought in March".
- Switching to FIFO later changes historical reported numbers, which is unsettling for a user
  even though no underlying data changed. Migration would need a cut-over date.

## Alternatives considered

| Alternative | Why not |
|---|---|
| **FIFO lots** | More correct for tax, and what a real broker does. Costs a `txn_lot` table, lot-aware projection, lot reconstruction on rebuild, and lot-level FX. About a day, on the critical path, for a property the customer did not ask for and the demo does not show |
| **Specific identification** | FIFO's cost plus a lot-selection UI. Not remotely justified |
| **Both, selectable per portfolio** | Doubles the test matrix on the most important logic in the product. A textbook example of building the flexible version before knowing whether anyone wants either |
| **LIFO** | Rarely permitted, rarely expected, no advantage here |

## Revisit when

- The product is used for anything tax-adjacent. This becomes mandatory, not optional.
- A user asks to see individual lots — the first real signal that WAC is losing information they care about.
- Any market or account type is added where lot tracking is a regulatory requirement.

**Migration sketch, so the door stays visibly open:** add `txn_lot`, add `CostBasisMethod` to
`ProjectionContext`, implement `FifoProjectionStrategy` alongside the existing WAC one,
backfill lots by replaying `txn` (the data is all there — that is ADR-0002 paying off), and
switch per portfolio behind a flag. Estimated one to two days, and it needs no schema change
to `txn`.
