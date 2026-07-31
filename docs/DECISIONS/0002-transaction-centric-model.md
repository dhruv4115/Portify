# ADR-0002 · Transactions are the source of truth; holdings are a projection

**Status:** Accepted · **Date:** Day 0 · **Owner:** Dev A · **Supersedes:** —

## Context

There are two ways to model a portfolio.

**Holding-centric:** `holding` is the real table. A BUY updates the row in place. Simple,
fast, and the natural first instinct.

**Transaction-centric:** `txn` is an append-mostly ledger and `holding` is a cached
projection recomputed from it.

The customer's fourth priority is *remove an item*. That single requirement decides it. Under
a holding-centric model, deleting a transaction from the middle of the history means
computing an inverse operation — and weighted-average cost has no exact inverse. If you buy
10 at 100 and 10 at 200, your average is 150. Delete the first transaction and the average
must become 200, but from `(quantity=20, avgCost=150)` alone you cannot recover that. The
information needed to undo the operation was destroyed when it was applied.

You can work around it by storing enough history to invert — at which point you have built a
transaction log with extra steps and worse guarantees.

There is a second reason. This is a portfolio system at an investment bank. A ledger of what
happened, from which current state is derived, is how the domain actually works. Positions
are reported *from* trades; trades are not reported from positions.

## Decision

**`txn` is the source of truth. `holding` is a projection, rebuildable from `txn` at any time,
and correctness is defined as "matches a full rebuild".**

Concretely:

1. Every write appends to `txn`, then recomputes the affected projection **inside the same
   database transaction**. Never two transactions, never an async job.
2. Deleting a transaction triggers a **full rebuild of that portfolio's projection** from the
   remaining rows. There is no inverse-operation code path, so there is nothing to drift.
3. The rebuild uses the **same `ProjectionEngine`** as the ordinary append. One code path,
   exercised by every write, so the rarely-used path cannot rot.
4. `ProjectionEngine` is a pure function: `(List<Txn>, ProjectionContext) → ProjectionResult`.
   No Spring, no database, no clock.
5. Concurrency is handled with `SELECT … FOR UPDATE` on the `portfolio` row. All writes to one
   portfolio serialise; different portfolios do not contend.
6. `ProjectionRebuildConsistencyIT` rebuilds **every** portfolio and asserts zero diff against
   the live table. That test is the definition of the invariant.

## Consequences

**Good**

- Deleting a mid-history transaction is correct by construction, not by careful inverse logic.
- Any projection bug is repairable by replaying — data is never lost, only the cache is wrong.
- Full audit history for free. "How did this position get here" is a query, not an investigation.
- The engine being a pure function means the hardest logic in the product is also the cheapest
  to test. ~90 % coverage on it costs almost nothing (see `/docs/TEST_PLAN.md`).
- **Multi-currency falls out of it.** Because valuation is a replay, running the same fold with
  an FX conversion produces base-currency figures without storing anything currency-dependent.
  ADR-0011 depends entirely on this decision.
- It is the same shape as event sourcing, without a broker, a schema registry or eventual
  consistency. We get the benefit and skip the operational cost.

**Bad, and accepted**

- A write costs more: read the history, fold, upsert. At our scale (hundreds of transactions
  per portfolio) this is single-digit milliseconds. It would matter at a million.
- The `FOR UPDATE` lock serialises writes to one portfolio. Correct, and irrelevant for a
  single-user-per-portfolio product.
- Two things can disagree — the projection and its source. Mitigated by rebuilding in the same
  transaction and by asserting the invariant in CI.
- `holding` is a cache, and someone will eventually treat it as authoritative. Hence rule 3
  and the naming: the table is a projection, and the ADR says so.

## Alternatives considered

| Alternative | Why not |
|---|---|
| **Holding-centric with in-place updates** | Cannot correctly delete a mid-history transaction under weighted-average cost. Fails customer priority 4 |
| **Holding-centric plus a compensating-entry log** | Never delete, only append a reversal. Correct, and it is how real ledgers work — but the *displayed* history then contains entries the user did not make, which is confusing in a personal portfolio tool. Revisit if this becomes a regulated system |
| **Full event sourcing with Kafka** | Same benefits, plus a broker, a schema registry, consumer lag and eventual consistency. `txn` in MySQL is already an event log with ACID guarantees |
| **Recompute on every read, no `holding` table at all** | Genuinely tempting and even simpler. Rejected because the reference-design schema has the table, the `holding` row carries `realised_pnl` across a sell-to-zero, and a read-time-only model makes the holdings list O(transactions) on every page load |

## Revisit when

- A portfolio exceeds ~50,000 transactions, at which point incremental projection becomes worth its complexity.
- Corporate actions arrive. They fit naturally — a split is another entry in the fold — but they change what "replay" means for historical prices.
- The system becomes regulated and immutability of the displayed history becomes a requirement, at which point the compensating-entry alternative wins.
