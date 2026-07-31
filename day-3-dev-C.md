# Day 3 — Dev C — The customer's four verbs, end to end · **MVP DAY**

## You have no prior context. Read this whole file before doing anything.

You are **Dev C** on a three-person team building **Protify**, a Portfolio Management REST API
(Java 21, Spring Boot 3.5.16, MySQL 8.4, `NamedParameterJdbcTemplate` — **no JPA**) plus a
React frontend. Base package `com.protify.portfolio`.

**You own:** `portfolio-api` (controllers, `dto/`, `mapper/`, `error/`, `graphql/`), GitHub
Actions, Flyway `V20`–`V29`, and most frontend work.

**Already merged:** Google sign-in; `GET /me`; `GlobalExceptionHandler` with RFC 9457
ProblemDetail; portfolio and instrument controllers; the DTO layer with money-as-string; the
API contract, **frozen**. Dev A lands `TransactionService`, `ValuationService` and
`PerformanceService` today. Dev B has resilient market data and FX.

> ## Today is the MVP gate, and the last task is the one that decides it
> **`D3-C4` — the React chart, add form and delete — is on the critical path.** Two of the
> customer's four priorities are only demonstrable through the UI. A working chart with three
> endpoints beats four perfect endpoints and no chart. Do not leave the frontend until 16:00.

**Read first:** `/docs/API_CONTRACT.md` §7–§12 · `/docs/PLAN.md` §9 (the descope ladder) ·
`/docs/TEST_PLAN.md` §4.

---

## D3-C1 · `TransactionController` — 2.0 h · 🔴 the customer's "add" and "remove"

`portfolio-api/…/api/transaction/TransactionController.java` + `CreateTransactionRequest`,
`TransactionResponse`

| | | |
|---|---|---|
| `POST` | `/portfolios/{id}/transactions` | 201 + `Location` + **`warnings[]`** |
| `GET` | `/portfolios/{id}/transactions` | 200, paged, filter by `type`, `symbol`, `from`, `to` |
| `DELETE` | `/portfolios/{id}/transactions/{txnId}` | 204 |

Bean Validation on `CreateTransactionRequest` (`/docs/API_CONTRACT.md` §8):
`@NotNull type` · `@DecimalMin("0.000001") quantity` · `@DecimalMin("0.0000") price` ·
`@NotNull currency` · **`@PastOrPresent executedAt`** · `@Size(max=500) note`.
`symbol` is required for BUY/SELL/DIVIDEND/FEE and must be absent for DEPOSIT/WITHDRAWAL —
that is a cross-field rule, so use a custom class-level constraint, not a controller `if`.

`warnings` is `[]` when empty, **never `null`**. It carries the "purchase exceeds available
cash" message — a warning, not a rejection (`/docs/PLAN.md` §2.7).

`TransactionResponse` carries `totalNative`, `totalBase` and `fxRateApplied` — the rate at the
transaction's date, not today's. That field is what makes the multi-currency story visible in
the API.

**Tests — `TransactionControllerTest`, 20 cases:** happy BUY/SELL/DEPOSIT; quantity 0 → 400;
negative quantity → 400; future `executedAt` → 400; missing symbol on a BUY → 400; symbol
supplied on a DEPOSIT → 400; unknown symbol → 404; currency mismatch → 422; SELL over holding →
422 with `type` `/errors/insufficient-quantity`; BUY over cash → 201 with a warning; no token →
401; another user's portfolio → **404**; delete happy → 204; delete unknown txn → 404; delete
another user's txn through your own portfolio path → **404**; paging; filter by type; filter by
date range; `from > to` → 400.

---

## D3-C2 · `HoldingController` + `ValuationController` — 1.5 h · 🔴 the customer's "browse"

- `GET /portfolios/{id}/holdings?currency=&includeZero=` (§10)
- `GET /portfolios/{id}/valuation?asOf=&currency=` (§11)

**The currency rule, and getting it backwards is the easiest mistake available:** `avgCost` and
`lastPrice` are in the **instrument's native** currency; `marketValue`, `costBasis` and
`unrealisedPnl` are in the portfolio's **base** currency. `/docs/API_CONTRACT.md` §0.3.

`includeZero=false` by default — a position sold to zero keeps its row (to preserve realised
P&L) but is filtered out of the default response.

Every response carries `dataQuality { priceAsOf, rateAsOf, stale }` from Dev B's services. It
is how the UI shows honest, degraded data instead of failing.

**Tests:** three-currency portfolio renders `avgCost` natively and totals in base; `?currency=USD`
re-expresses everything; `includeZero=true` shows the closed position with its realised P&L;
empty portfolio → `200 []`, never 404; weights sum to 100 ± 0.01; unknown currency → 400;
another user's → 404.

---

## D3-C3 · `PerformanceController` — 0.5 h · 🔴 the customer's "view performance"

`GET /portfolios/{id}/performance?from=&to=&interval=&currency=` (§12)

Defaults: `from` = one year ago, `to` = today, `interval=DAILY`.
`from > to` → 400 with a field error. Range over 5 years → 400.

**Tests:** happy path returns one point per day; `from > to` → 400; range too wide → 400;
empty portfolio → `points: []` with a valid summary, **not a divide-by-zero**; 401; 404.

---

## D3-C4 · The React app — 2.5 h · 🔴 **THE CRITICAL TASK — start by 15:00 at the latest**

`protify-frontend/src/pages/PortfolioDetail.tsx` and `src/components/PerformanceChart.tsx`

Four things, in this order of importance:

1. **Performance chart** (Recharts `LineChart`) — total value over time, tooltip showing date and value in the base currency.
2. **Holdings table** — symbol, quantity, avgCost (native), lastPrice (native), marketValue (base), P&L, weight.
3. **Add-transaction form** — symbol type-ahead against `GET /instruments`, type, quantity, price, date. On a 422, show `ProblemDetail.detail` **inline against the right field**, using the `errors[]` array.
4. **Delete** — confirm dialog naming the transaction; on success, holdings and chart both refresh.

Use the `formatMoney` helper. **Never `parseFloat` an amount you intend to calculate with** —
all arithmetic is server-side.

Handle these states or the demo looks broken: loading skeletons, empty portfolio ("Add your
first transaction"), a chart with one point, a chart with zero points, and a `stale: true`
badge when data is old.

**Tests — Vitest:** `PerformanceChart` renders with 0, 1 and 200 points; the add form maps a
422 to the correct inline field error.

---

## Rules

- **DTOs are `record`s** with Bean Validation. Persistence types never cross the controller boundary.
- **No business logic in a controller.** Validate, delegate, map.
- **All errors through `GlobalExceptionHandler`.**
- **No `double`, no `float`.** Money is `BigDecimal` in Java, a **string** in JSON.
- **Another user's resource is 404, never 403.**
- **`portfolio-common` is frozen. You may not edit `pom.xml`.**
- The API contract is **frozen** — if the code needs to differ, raise it in the channel before changing either.

## Do not touch

`portfolio-core/**` (Dev A) · `portfolio-platform/**` (Dev B) · `portfolio-common/**` ·
any POM · migrations `V1`–`V19`.

---

## If you are behind at 15:00

Execute `/docs/PLAN.md` §9 and say so at stand-up:
1. Drop the transaction **filters** — keep POST, plain GET and DELETE
2. Drop `?currency=` presentation override — base currency only
3. Drop `includeZero`
4. Replace the chart with a **table of daily values** — priority 2 is *view performance*, not *view a chart*

**Never drop the add form or delete.** Those are customer priorities 3 and 4.

---

## Done when

- [ ] POST returns 201 with `Location` and `warnings[]`
- [ ] SELL over holding → 422 with the right `type` slug
- [ ] Unknown symbol → 404; currency mismatch → 422
- [ ] Another user's portfolio and another user's transaction both → **404**
- [ ] Holdings show native `avgCost` and base-currency totals
- [ ] Performance returns one point per day; empty portfolio does not divide by zero
- [ ] **The full loop works in a browser: browse → chart → add → remove**
- [ ] `mvn clean verify` green; merged to `develop` by 17:30

## Hand-off

**Tonight's demo is the MVP and you drive it.** In the browser: sign in, open a portfolio
holding AAPL (USD), RELIANCE (INR) and SHEL (GBP), show everything totalled in INR, show the
performance chart, add a transaction, watch the chart move, delete it, watch it move back.
Then show an invalid sell producing a clean 422.

**Ask Dev A to tag `v0.1-mvp` once it is green.**

Tomorrow: `CrossUserAccessIT` — the parameterised test proving user A cannot touch any of user
B's data across every endpoint. It is the test an assessor at an investment bank looks for
first, and tomorrow you and Dev A will deliberately remove a `WHERE user_id` clause to prove
it goes red.
