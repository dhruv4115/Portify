# Day 3 — Dev B — Refresh, rate limiting, resilience · **MVP DAY**

## You have no prior context. Read this whole file before doing anything.

You are **Dev B** on a three-person team building **Protify**, a Portfolio Management REST API
(Java 21, Spring Boot 3.5.16, MySQL 8.4, `NamedParameterJdbcTemplate` — **no JPA**) plus a
React frontend. Base package `com.protify.portfolio`.

**You own:** `portfolio-platform` (`security`, `user`, `marketdata`, `fx`, `config`, `web`,
`health`), `scripts/`, `docker/`, `Jenkinsfile`, Flyway `V10`–`V19`.

**Already merged:** Google sign-in and JIT user provisioning; correlation IDs; two market-data
adapters behind the port; `CachingMarketDataService` with a `price_history` fallback;
`V10__fx_rate.sql`; `FrankfurterFxRateProvider` and `CachingFxRateService`; `V11`/`V12` seeded
with two years of real prices and rates.

**Today is the MVP gate for the team.** Your job is to make sure that when the network
misbehaves — and on a bank guest network it will — nothing the user sees breaks.

**Read first:** `/docs/ARCHITECTURE.md` §7 · `/docs/DECISIONS/0008-market-data-provider-port.md`
· `/docs/RISKS.md` R4, R11 · `/docs/API_CONTRACT.md` §0.5, §17.

---

## D3-B1 · Scheduled price refresh — 1.5 h · 🔴

`portfolio-platform/…/platform/marketdata/PriceRefreshScheduler.java`

`@Scheduled` daily refresh writing into `price_history`, plus the service behind
`POST /admin/prices/refresh` (Dev C exposes the endpoint; you provide the service).

**The governing rule: nothing on a request path calls a provider.** Providers are invoked only
by this scheduler and by the explicit admin trigger. A user loading a page must never be able
to trigger a rate limit — that is `/docs/RISKS.md` R4 and it is the difference between a demo
that works and one that 429s in front of the assessor.

- Upsert on `(instrument_id, price_date)`; refreshing twice inserts no duplicate.
- **Partial success is the normal outcome**, not an error. Per-symbol results:
  `{symbol, status: UPDATED|SKIPPED|FAILED, rowsWritten, latestDate, source}`, and the overall
  response is still 200. See `/docs/API_CONTRACT.md` §17.
- Restrict the admin trigger to the emails in `ADMIN_EMAILS`; anyone else gets 403.

**Tests:** `PriceRefreshSchedulerTest` — a provider failure on one symbol does not abort the
rest. `PriceRefreshIT` — refreshing twice produces no duplicate rows.

---

## D3-B2 · Rate limiting and the circuit breaker — 2.0 h · 🔴

`portfolio-platform/…/platform/marketdata/RateLimitedProvider.java`

A decorator around any `MarketDataProvider`:

- **Token bucket** sized to the configured tier (Twelve Data free is 8/min, 800/day; Yahoo is undocumented, so use a conservative 30/min).
- **Exponential backoff with jitter** on 429 and 5xx. Jitter matters — without it, three developers' schedulers synchronise and hit the provider together.
- **Circuit breaker:** 3 consecutive failures opens it for 5 minutes. While open, **do not call the provider at all** — serve cache, and let `CachingMarketDataService` fall through to `price_history`.
- Log every state transition with the correlation ID.

Hand-roll it — roughly 60 lines. Resilience4j would be better with a spare half-day, and that
trade-off is recorded in ADR-0008; do not add the dependency today, and remember you cannot
edit `pom.xml` anyway.

**Tests — `RateLimitedProviderTest`:** 50 rapid requests never exceed the bucket; a simulated
429 triggers backoff and **no exception escapes to the caller**; 3 failures open the circuit;
while open the provider is **not called** (verify with Mockito `never()`); it half-opens after
5 minutes. Use an injected `Clock` — no `Thread.sleep` in tests.

---

## D3-B3 · FX refresh and on-demand historical backfill — 1.5 h · 🔴

`portfolio-platform/…/platform/fx/FxRefreshScheduler.java`

- Daily refresh of the four currencies against the USD pivot.
- **On-demand historical backfill:** Dev A's performance series will ask for a rate on a date we have never fetched. Fetch that range from Frankfurter, store it, and serve it. Fetch **once** — a second request for the same date must hit the database, not the provider.
- Never a future rate. A date with no row resolves to the most recent row **on or before** it.

**Tests — `FxRefreshSchedulerTest`:** an unseen date is fetched and stored; the same date twice
calls the provider once; a provider failure falls back to last-good and reports an honest
`rateAsOf`; no future rate is ever returned.

---

## D3-B4 · Health indicators — 1.0 h

`portfolio-platform/…/platform/health/`

Custom indicators for `db`, `marketdata` and `fx`.

**Health stays UP with the network off**, reporting `"marketdata": "DEGRADED"`. A stale price is
not an outage — the data is there, it just has an older date on it. Reporting DOWN would be
wrong and would look alarming in a demo for no reason.

`db` genuinely down **is** DOWN.

**Test — `HealthIndicatorTest`:** all providers throwing → status UP, market data DEGRADED.

---

## D3-B5 · Demo script first draft — 0.5 h · 🟢 can slip

`/docs/DEMO_SCRIPT.md` — the click-by-click path for Day 6. Someone who has not built the
product must be able to run the demo from it. Include the deliberate failure case (an invalid
sell showing a clean 422) and the offline fallback moment.

---

## Rules

- **`portfolio-common` is frozen.**
- **No `double`, no `float`.** FX at scale 8, money at 4.
- **No JPA, no Hibernate, no Spring Data. No `JdbcTemplate` outside a `*Repository`.**
- **You may not edit `pom.xml`.** Ask Dev A — but note the whole point of D3-B2 is that you need no new dependency.
- Migrations only in `V10`–`V19`.
- **No test calls a real provider.** Mock server or stub bean.
- **No hard-coded FX rate**, including in a fixture that pretends to be production config.
- Never let a provider failure become a 5xx. Degrade, flag, carry on.

## Do not touch

`portfolio-core/**` (Dev A) · `portfolio-api/**` (Dev C) · `portfolio-common/**` ·
any POM · migrations `V1`–`V9`, `V20`+.

---

## Done when

- [ ] Scheduled refresh writes new rows; running twice produces no duplicates
- [ ] Admin refresh returns per-symbol results; partial failure is still 200
- [ ] 50 rapid requests never exceed the token bucket
- [ ] A simulated 429 produces **no 5xx to any caller**
- [ ] 3 failures open the circuit; the provider is not called while it is open
- [ ] An unseen FX date is fetched once and served from the database thereafter
- [ ] No future FX rate is ever returned
- [ ] Health is UP with everything offline, market data DEGRADED
- [ ] `mvn clean verify` green; merged to `develop` by 17:30

## Hand-off

Confirm to the team that **turning the wifi off does not break any read endpoint.** Try it
today rather than discovering it on Day 6 — it takes two minutes and it is the single most
valuable thing you can verify this week.

Tomorrow you build the multi-stage Dockerfile, `compose.yml`, and the `Jenkinsfile` — and you
get a **real green Jenkins run from a real push**. Take a screenshot of it tomorrow, not on
Day 6: `/docs/RISKS.md` R8 is that the VM is a single point of failure, and a Day 4 screenshot
is insurance against a Day 6 outage.
