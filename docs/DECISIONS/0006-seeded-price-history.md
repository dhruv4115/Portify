# ADR-0006 · Two years of real price history seeded in a migration

**Status:** Accepted · **Date:** Day 0 · **Owner:** Dev B · **Supersedes:** —

## Context

Two requirements pull in opposite directions.

1. **The customer wants real market data.** Not the instructor's dummy API, not random walks.
   Real prices from a real provider.
2. **The demo must work with no internet.** Corporate wifi, a captive portal, or a guest
   network that blocks an unknown outbound host — any of these kills a live-provider demo in
   front of the assessor.

There is a third pressure that is easy to miss until Day 3: **the performance chart needs
history.** A portfolio created on Day 2 with transactions dated Day 2 produces a one-point
chart. To demonstrate "view performance" — the customer's second priority — we need
transactions dated months back, and those need prices on every day in between. Fetching two
years of daily closes for 18 instruments on demand is roughly 9,000 rows and, on a free tier
allowing 8 requests a minute, a rate-limit violation waiting to happen.

## Decision

**A Python script fetches two years of real daily closes with `yfinance`, offline and
one-shot, and emits them as a Flyway migration. The migration ships in the repository.**

```
scripts/backfill_prices.py  →  src/main/resources/db/migration/V11__seed_price_history.sql
                            →  src/main/resources/db/migration/V12__seed_fx_rate.sql
```

- The data is **real** — actual historical closes for AAPL, RELIANCE, SHEL and the rest, and
  actual ECB reference rates. Not generated, not perturbed.
- `source = 'SEED'` on every seeded row, so a seeded price is always distinguishable from a
  freshly-fetched one. `GET /instruments/{symbol}/prices` exposes it.
- `INSERT IGNORE` on the unique `(instrument_id, price_date)`, so the migration is idempotent
  and a live refresh never conflicts with it.
- **This does not replace live integration.** `MarketDataProvider` still calls a real provider
  on a schedule and writes newer rows. The seed is the floor of the fallback chain
  (`/docs/ARCHITECTURE.md` §7), not the data source.
- Re-running the script regenerates the migration. Because the migration is checksummed by
  Flyway once applied, regeneration during development means a fresh database — which is why
  it is a Day 2 task, before anyone has data worth keeping.

## Consequences

**Good**

- **The demo cannot fail because of a network.** This is the point, and it is worth more than
  every stretch feature combined.
- Real prices, so charts show real market behaviour — actual drawdowns, actual volatility.
  Random walks look wrong to anyone who has seen a price series, and this assessor has.
- The performance chart is interesting from the first run: a fresh database has 18 months of
  history and the Day 6 demo portfolio (`V4`) has transactions across it.
- Development needs no API key and burns no quota. Three developers hitting a free tier all
  week would exhaust it by Day 2.
- Tests get a large, realistic, deterministic dataset for free. `PerformanceBudgetIT` runs
  against 500 transactions and 365 days of real prices.
- yfinance covers NSE, LSE and NASDAQ, so multi-currency seed data comes from one source.

**Bad, and accepted**

- **~9,000 `INSERT` rows in the repository.** The migration is a large file. Mitigated by
  multi-row inserts batched 500 at a time; it applies in a few seconds. It is data, it is
  reviewed once, and it never changes.
- Seeded prices go stale. After a month, an unrefreshed database shows month-old prices —
  which is exactly what `dataQuality.priceAsOf` and the `stale` flag are for. Visible, not hidden.
- `yfinance` is an unofficial scraper of a public endpoint and could break. It runs offline,
  once, on a developer machine — if it breaks we regenerate from another source, and nothing
  in the shipped product depends on it.
- Committing market data raises a licensing question. For a supervised training project with
  a small sample of public closing prices this is acceptable; a commercial product would need
  a licensed source. Noted rather than dismissed.

## Alternatives considered

| Alternative | Why not |
|---|---|
| **Fetch everything live, no seed** | Satisfies "real data" perfectly and fails the offline requirement completely. One bad network on Day 6 and there is no demo |
| **Generate synthetic prices (random walk)** | Offline-safe, no licensing question, tiny migration. Rejected because the customer explicitly said no dummy data, and because synthetic series look wrong — no gaps, no jumps, wrong volatility clustering |
| **Seed a CSV and load it at application startup** | Smaller repo diff. Rejected because it puts data loading outside Flyway, so the database's state depends on boot order rather than on migration version. Two sources of truth for schema state |
| **Docker image with a pre-populated MySQL volume** | Fast startup, but the data becomes invisible — not reviewable, not diffable, and it breaks anyone running against native MySQL, which is the local setup |
| **Seed only 3 months** | Smaller file. Rejected because a 3-month chart is a thin demo of the customer's second priority, and because annualised-return analytics need more than one quarter to be meaningful |

## Revisit when

- The seed file becomes painful to review or slow to apply — trim to one year, or move to a
  compressed loader invoked by a Java-based Flyway migration.
- A licensed market-data source is available, at which point seeding may be contractually
  restricted and the fallback would need to be synthetic or absent.
- The product runs somewhere with guaranteed connectivity and the offline requirement is
  genuinely dropped. It is unlikely to be dropped: offline resilience is good engineering
  regardless of the demo.
