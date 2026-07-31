# Day 2 — Dev B — Market data, FX, and the offline safety net

## You have no prior context. Read this whole file before doing anything.

You are **Dev B** on a three-person team building **Protify**, a Portfolio Management REST API
(Java 21, Spring Boot 3.5.16, MySQL 8.4, `NamedParameterJdbcTemplate` — **no JPA**) plus a
React frontend. Base package `com.protify.portfolio`.

**You own:** `security/`, `user/`, `marketdata/`, `fx/`, `config/`, `web/`, `insights/`,
`scripts/`, `docker/`, `Jenkinsfile`, Flyway `V10`–`V19`.

**Already merged:** Google sign-in works end to end; `GET /me` provisions a user;
`CorrelationIdFilter`; Dev A's `MoneyUtils`, enums, exceptions, and the two **ports** you
implement today. `common/` is frozen.

**Today you build the reason the demo cannot fail.** Every external call gets a cache and a
fallback, and by tonight a real market price appears in the app.

**Read first:** `/CLAUDE.md` · `/docs/ARCHITECTURE.md` §7 (the fallback chain) ·
`/docs/DECISIONS/0008-market-data-provider-port.md` · `0006-seeded-price-history.md` ·
`0011-multi-currency-valuation.md` · `/docs/RISKS.md` R4, R14.

---

## D2-B1 · Market-data provider adapters — 2.0 h · 🔴

`marketdata/provider/`

Implement Dev A's `MarketDataProvider` interface (in `common/`) twice:

**`YahooMarketDataProvider`** — default. Keyless, global coverage.
`https://query1.finance.yahoo.com/v8/finance/chart/{symbol}?interval=1d&range=…`

**`TwelveDataMarketDataProvider`** — key from `TWELVE_DATA_API_KEY`. **Auto-disables itself
when the key is absent** rather than failing at startup.

Selection is `MARKET_DATA_PROVIDER=yahoo|twelve_data` — **configuration, never code**. Use
`RestClient`, 3-second timeout, one retry.

**Two adapter rules that are easy to get wrong and expensive to miss:**

1. **Symbol mapping lives here, not in the database.** `instrument.symbol` holds the clean
   symbol (`RELIANCE`, `SHEL`); the adapter appends the provider suffix (`.NS`, `.L`). Agree
   the mapping with Dev A — it is your adapter's business, not their data's.

2. **GBX → GBP normalisation happens here, before anything is stored.** London instruments
   quote in **pence**. Shell comes back as `2915`, meaning £29.15. Store it raw and every GBP
   position is 100× too valuable, which cascades into every total, weight and chart on the
   page — and the code looks correct because only the unit is wrong. `instrument.currency` is
   never `GBX`. This is `/docs/RISKS.md` R14.

**Tests:** `YahooMarketDataProviderTest` with `MockRestServiceServer` — parses a real captured
response; **`shouldConvertGbxToGbpOnIngestion`**; a 429 does not throw; a timeout does not
throw. `TwelveDataMarketDataProviderTest` — disabled when no key. **No test calls a real
provider.**

---

## D2-B2 · `CachingMarketDataService` — 2.0 h · 🔴

`marketdata/CachingMarketDataService.java` + `PriceHistoryRepository`

The fallback chain, in order:

```
Caffeine (15 min TTL)
  → HTTP provider (3 s timeout, 1 retry)
    → price_history most recent row on or before the date
      → V11 seeded rows (always present)
```

**This service never throws on provider failure.** It degrades and reports `priceAsOf`. A
stale price is data with a date on it, not an error. Anything that would have been a 502
becomes a 200 with an older `priceAsOf` and `stale: true`.

`PriceHistoryRepository` — explicit SQL, hand-written `PriceHistoryRowMapper`, upsert via
`INSERT … ON DUPLICATE KEY UPDATE` on the `(instrument_id, price_date)` unique key.

Return `priceAsOf` alongside every price. Dev C surfaces it as `dataQuality` in every response
carrying a market value (`/docs/API_CONTRACT.md` §0.5).

**Tests — `CachingMarketDataServiceTest`:** cache hit does not call the provider; provider
throws → DB row used; DB empty → seed used; the second call within 15 minutes calls the
provider **once**; `priceAsOf` reflects the real date of whatever was served.

---

## D2-B3 · FX — `V10__fx_rate.sql` + provider + caching service — 2.0 h · 🔴

**Migration `V10__fx_rate.sql`** (your range):

```sql
CREATE TABLE fx_rate (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    base_ccy    CHAR(3)       NOT NULL,
    quote_ccy   CHAR(3)       NOT NULL,
    rate_date   DATE          NOT NULL,
    rate        DECIMAL(19,8) NOT NULL,
    source      VARCHAR(32)   NOT NULL,
    fetched_at  DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_fx UNIQUE (base_ccy, quote_ccy, rate_date),
    INDEX idx_fx_date (rate_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

`DECIMAL(19,8)` — FX needs more precision than money.

**`FrankfurterFxRateProvider`** — `https://api.frankfurter.app/{date}?from=USD&to=INR,GBP,EUR`.
Keyless, ECB reference rates, historical endpoint included.

**`CachingFxRateService`** — 6-hour TTL, then `fx_rate` last-good, then `V12` seed.

**Store against a USD pivot.** One row per currency per day; a cross rate is
`rate(USD→quote) ÷ rate(USD→base)`. Storing pairs directly is O(n²) rows for no gain
(ADR-0011).

**Rate on a date with no row: the most recent row on or before it. Never a future rate** — a
future rate invents history, and it will pass a naive test.

**Never hard-code an FX rate.** Not in code, not in a config default, not in a fixture that
pretends to be production config.

**Tests:** `FxRateServiceTest` — conversion correct; USD→INR→USD round-trips within 0.0001;
provider down → last-good used and `rateAsOf` reports the real date; missing date → most recent
prior; cross rate derived correctly. `FxRateRepositoryIT` — upsert idempotent.

---

## D2-B4 · Python backfill + seed migrations — 0.5 h

`scripts/backfill_prices.py` — `yfinance`, **two years of daily closes** for all 18 seeded
instruments, emitted as `V11__seed_price_history.sql`. Plus two years of daily FX from
Frankfurter as `V12__seed_fx_rate.sql`.

- `source = 'SEED'` on every row, so seeded data is always distinguishable from fetched data.
- `INSERT IGNORE`, batched ~500 rows per statement, so the migration is idempotent and applies in seconds.
- Apply the same GBX → GBP conversion here. A mismatch between the script and the Java adapter is a bug that only shows up in the chart.
- Re-running the script regenerates the migration. Do it **today**, before anyone has data worth keeping — Flyway checksums an applied migration, so regenerating later means a fresh database for everyone.

**Test — `SeedPriceHistoryIT`:** every seeded instrument has ≥ 480 rows; no seeded GBP
instrument has a close over 1000 (a 100× error is invisible in an assertion nobody wrote).

---

## Rules

- **`common/` is frozen.** Implement the ports as they are. Need a change? Team agreement first.
- **No `double`, no `float`.** FX rates are `BigDecimal` at scale 8.
- **No JPA, no Hibernate, no Spring Data.**
- **No `JdbcTemplate` outside a `*Repository`.**
- **You may not edit `pom.xml`.** You need `caffeine` today — ask Dev A this morning.
- Migrations only in `V10`–`V19`, only new ones.
- **No test calls a real provider.** `MockRestServiceServer` or a stub bean.
- No API key in the repository. Env var with a safe default.

## Do not touch

`instrument/`, `portfolio/`, `transaction/`, `holding/`, `valuation/`, `support/` (Dev A) · `api/`, `graphql/` (Dev C) · `common/` — frozen ·
any POM · migrations `V1`–`V9`, `V20`+.

---

## Done when

- [ ] Both market-data adapters implement the port; provider chosen by config alone
- [ ] GBX → GBP normalisation tested, in both the Java adapter and the Python script
- [ ] `CachingMarketDataService` never throws — provider failure, 429 and timeout all covered
- [ ] `V10__fx_rate.sql` applied; FX conversion correct; USD pivot with derived cross rates
- [ ] FX falls back to last-good and reports an honest `rateAsOf`
- [ ] `V11` and `V12` generated: 2 years of real prices and real rates, ≥ 480 rows each
- [ ] No hard-coded FX rate anywhere
- [ ] `mvn clean verify` green; merged to `develop` by 17:30

## Hand-off

Tell Dev A the exact shape of what valuation will call tomorrow: `priceFor(instrumentId, date)`
and `convert(Money, targetCurrency, date)`, both returning the value **and** its `asOf` date.
They need both tomorrow morning.

Tomorrow you build the scheduled refresh, rate limiting with a circuit breaker, on-demand
historical FX backfill, and the health indicators. Your job tomorrow is making sure that
nothing on a **request path** ever calls a provider — refresh is scheduled or explicitly
triggered, never triggered by a user loading a page.
