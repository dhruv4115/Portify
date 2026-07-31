# ADR-0008 · Market data and FX behind ports; no provider reaches the domain

**Status:** Accepted. Note: [ADR-0012](0012-single-module.md) (Day 1) removed the separate
`portfolio-common`/`portfolio-platform` Maven modules referenced below — both are now packages
(`common/`, `marketdata/`, `fx/`) in one module, and the compile-time guarantee that
`portfolio-core` cannot import `portfolio-platform` no longer holds; the decision to route
market data and FX through these two ports is otherwise unaffected · **Date:** Day 0 ·
**Owner:** Dev B · **Supersedes:** —

## Context

The customer requires real market data and real FX rates, but has **not** confirmed a provider
or an API key. That is not a gap to be closed later — it is a constraint on the design today.
Whatever we build must survive the provider being chosen, changed, rate-limited or replaced
mid-project.

The candidates differ in ways that matter:

| Provider | Key | Free limit | Coverage | Risk |
|---|---|---|---|---|
| **Yahoo chart endpoint** | none | undocumented | global — NASDAQ, NSE, LSE | unofficial, unversioned, can break without notice |
| **Twelve Data** | yes | 8/min, 800/day | good, US-centric on free tier | needs a key we do not have |
| **Alpha Vantage** | yes | 25/day | broad | 25/day is unusable for development |
| **yfinance (Python)** | none | scraper | global | not callable from Java |
| **Frankfurter (ECB)** | none | generous | FX only, ~30 currencies | daily rates only, ECB business days |

None is safe to depend on. Yahoo is the best coverage-per-setup-effort and the worst
stability; Twelve Data is stable and needs a key that may never arrive.

## Decision

**Two ports in `portfolio-common`, implemented by adapters in `portfolio-platform`. No domain
code ever names a provider.**

```java
public interface MarketDataProvider {
    Optional<PriceQuote> latestPrice(String symbol);
    List<PriceQuote> dailyCloses(String symbol, LocalDate from, LocalDate to);
    String sourceName();
}

public interface FxRateProvider {
    Optional<FxQuote> rate(CurrencyCode from, CurrencyCode to, LocalDate on);
    Map<CurrencyCode, BigDecimal> ratesFor(CurrencyCode base, LocalDate on);
    String sourceName();
}
```

Adapters, all present on Day 2:

| Adapter | Role |
|---|---|
| `YahooMarketDataProvider` | default — no key, global coverage |
| `TwelveDataMarketDataProvider` | activated by `MARKET_DATA_PROVIDER=twelve_data` and a key; auto-disabled when the key is absent |
| `FrankfurterFxRateProvider` | FX, keyless, ECB reference rates |
| `scripts/backfill_prices.py` | yfinance, offline, one-shot, produces the seed migration (ADR-0006) |

**Selection is configuration, never code.** `MARKET_DATA_PROVIDER` and `FX_PROVIDER` choose the
bean; keys come from env vars with safe local defaults; nothing is committed.

The ports live in `common`, so `portfolio-core` can call them while being **unable to import
`portfolio-platform`** (ADR-0003). The valuation engine physically cannot reach an HTTP client.

Every call goes through `CachingMarketDataService` / `CachingFxRateService`, which own the
whole resilience chain (`/docs/ARCHITECTURE.md` §7):

```
Caffeine (15 min prices / 6 h FX)
  → circuit breaker (3 failures ⇒ open 5 min)
    → token bucket sized to the provider's tier
      → HTTP provider (3 s timeout, 1 retry)
        → price_history / fx_rate last-good row
          → V11 / V12 seeded data
```

**Nothing on a request path calls a provider.** Providers are invoked only by the scheduler and
by `POST /admin/prices/refresh`. A user page load can never trigger a rate limit.

Two adapter-level rules that exist because they are easy to get wrong:

- **GBX → GBP normalisation happens in the adapter**, before storage. LSE quotes in pence;
  storing `2915` as GBP makes a portfolio 100× too valuable (`/docs/RISKS.md` R14).
- **The service never throws on provider failure.** It degrades and reports `priceAsOf` /
  `rateAsOf`. A stale price is data with a date on it, not an error.

## Consequences

**Good**

- The provider decision can be deferred past Day 2 and changed on Day 5 with an env var.
- No key is required to build, test or demo — development proceeds today with no procurement.
- Testing is trivial: `MockRestServiceServer` against the adapter, a stub port everywhere else.
  **No test ever calls a real provider.**
- Rate limits and 429s are handled in one place rather than at every call site (R4).
- The offline requirement is satisfied structurally, not by a demo-day workaround.
- The ports are the first extraction seam: lifting market data into its own service is
  swapping the adapter for an HTTP client against the same interface.

**Bad, and accepted**

- Two interfaces, four adapters and two caching services for what could be one class calling
  one URL. Justified specifically by the provider being unknown — with a confirmed provider
  and key this would be over-engineering, and it should be judged that way if the situation changes.
- The ports must land on **Day 1**, before `portfolio-common` freezes, before either adapter is
  written. Getting an interface right before its implementation exists is a real risk (R10),
  mitigated by keeping both interfaces small and by both being read by all three developers.
- Yahoo remains an unofficial endpoint. If it starts returning captchas we fall back to seeded
  data and say so in the demo. Twelve Data is the escape hatch if a key appears.
- A cached price and a seeded price are both "not live". `dataQuality` makes the distinction
  visible in the response rather than leaving it to be inferred.

## Alternatives considered

| Alternative | Why not |
|---|---|
| **Call one provider directly from the valuation service** | Fastest to write and the reason this ADR exists. Couples the domain to an HTTP client, makes every valuation test need a mock server, and makes changing provider a refactor |
| **Commit to Twelve Data now and get a key** | Cleaner if the key arrives. We cannot start work on a procurement step with no committed date, and the whole market-data path is a Day 2 dependency |
| **Spring `RestClient` with a `@Cacheable` annotation and nothing else** | Gives caching but no circuit breaker, no rate limiting, no last-good fallback. The interesting failures are the ones it does not cover |
| **Resilience4j for the breaker and bucket** | Genuinely better than hand-rolling, and would be the choice with a spare half-day. Rejected on dependency and configuration cost for two call sites; ~60 lines of hand-written breaker is enough here and is fully tested |
| **A message queue between refresh and storage** | Solves a throughput problem we do not have |

## Revisit when

- A provider and key are confirmed — at which point one adapter can become the sole
  implementation and the second could be deleted, though keeping it costs nothing.
- Intraday or streaming prices are needed. The port grows a `subscribe` method or gains a
  streaming sibling; nothing in the domain changes.
- Rate limiting becomes a genuine constraint rather than a precaution — swap the hand-rolled
  bucket for Resilience4j at that point.
