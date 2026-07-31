# BACKLOG.md — Protify Portfolio Manager

Six days, three developers, one sprint. Not a two-week sprint cut in half — a six-day
structure with a demo every evening and an MVP gate on Day 3.

**Velocity assumption:** ~13 points per developer per day, ~39 team points per day,
**~234 points across Days 1–6.** Committed scope below is 198 points, leaving ~15 % for the
things that always appear.

---

## 1. MoSCoW at a glance

| | Points | Share | Meaning here |
|---|---|---|---|
| **Must** | 118 | 60 % | The customer's four verbs, plus auth. No demo without these |
| **Should** | 47 | 24 % | Materially improves the product; cuttable with a conversation |
| **Could** | 33 | 17 % | Showcase material. First to go, no conversation needed |
| **Won't (this sprint)** | — | — | Explicitly out. Listed in §8 so it is visibly a decision |

---

## 2. Epics

| Epic | Stories | Points | Customer priority |
|---|---|---|---|
| **E1 · Identity** | PM-1 … PM-4 | 26 | Precondition for everything |
| **E2 · Browse the portfolio** | PM-5 … PM-12 | 55 | **1st** |
| **E3 · View performance** | PM-13 … PM-18 | 44 | **2nd** |
| **E4 · Add items** | PM-19 … PM-23 | 31 | **3rd** |
| **E5 · Remove items** | PM-24 … PM-26 | 16 | **4th** |
| **E6 · Multi-currency** | PM-27 … PM-30 | 26 | Customer-added, cross-cutting |
| **E7 · Engineering quality** | PM-31 … PM-36 | 34 | Assessed, not requested |
| **E8 · Showcase** | PM-37 … PM-42 | 33 | Stretch |

---

## 3. Epic 1 — Identity

### PM-1 · Sign in with Google · **Must** · 8 pts · Dev B · **Day 1**
> As an investor, I want to sign in with my Google account, so that I never create another password.

- **Given** a valid, unexpired Google ID token, **when** I call any `/api/v1` endpoint, **then** I get a 200 and the response is scoped to me.
- **Given** an expired token, **when** I call an endpoint, **then** I get 401 with a ProblemDetail body and no stack trace.
- **Given** a token signed by a different key, **when** I call an endpoint, **then** I get 401 and the failure is logged with a correlation ID.
- **Given** a token whose `aud` is a different client ID, **when** I call an endpoint, **then** I get 401.
- **Given** no `Authorization` header, **when** I call an endpoint, **then** I get 401, not 403.

### PM-2 · Provision on first sign-in · **Must** · 5 pts · Dev B · **Day 1**
> As a new user, I want my account created automatically, so that there is no sign-up form.

- **Given** a `sub` never seen before, **when** I call `GET /me`, **then** an `app_user` row is created and returned with a 200.
- **Given** the same `sub` a second time, **when** I call `GET /me`, **then** no second row is created.
- **Given** `email_verified: false`, **when** I call `GET /me`, **then** I get 403 and no row is created.
- **Given** two concurrent first-time requests with the same `sub`, **when** both are handled, **then** exactly one row exists — the unique constraint on `google_sub` is caught and treated as a read.

### PM-3 · See my own profile · **Must** · 3 pts · Dev C · **Day 1**
> As a signed-in user, I want to see my name and picture, so that I know which account I am in.

- **Given** I am signed in, **when** the app loads, **then** my display name and picture render in the header.
- **Given** my Google account has no picture, **when** the app loads, **then** initials render instead and nothing errors.

### PM-4 · Nobody sees anyone else's data · **Must** · 10 pts · Dev C · **Day 4**
> As an investor, I want my portfolio invisible to every other user, so that I can trust the system with real holdings.

- **Given** user B's portfolio id, **when** user A requests it, **then** 404 — **not 403**, because 403 confirms it exists.
- **Given** user B's transaction id inside user A's own portfolio path, **when** user A deletes it, **then** 404 and user B's data is untouched.
- **Given** any user-scoped endpoint, **when** the parameterised cross-user test runs, **then** every one of them returns 404.
- **Given** a repository method, **when** it is reviewed, **then** its SQL contains a `user_id` predicate or it takes no user data.

---

## 4. Epic 2 — Browse the portfolio *(customer priority 1)*

### PM-5 · Create a portfolio · **Must** · 5 pts · Dev A + C · **Day 2**
- **Given** name "Growth" and base currency INR, **when** I POST `/portfolios`, **then** 201 with a `Location` header and the portfolio appears in my list.
- **Given** a name I already used, **when** I POST, **then** 409 with type `/errors/duplicate-portfolio-name`.
- **Given** a blank name, **when** I POST, **then** 400 with `errors[0].field == "name"`.
- **Given** base currency "XYZ", **when** I POST, **then** 400 and the message names the four supported currencies.

### PM-6 · List my portfolios · **Must** · 3 pts · Dev A + C · **Day 2**
- **Given** I have three portfolios, **when** I GET `/portfolios`, **then** all three return with a summary valuation each.
- **Given** I have none, **when** I GET, **then** `200` and `[]` — never 404.

### PM-7 · See portfolio detail · **Must** · 5 pts · Dev A + C · **Day 2**
- **Given** a portfolio with holdings, **when** I GET it, **then** market value, cost basis, cash and P&L all return in the base currency.
- **Given** a portfolio with no transactions, **when** I GET it, **then** all totals are `0.0000` and `unrealisedPnlPct` is **`null`**, not `Infinity` and not a 500.

### PM-8 · See my holdings · **Must** · 8 pts · Dev A + C · **Day 3**
- **Given** holdings in USD, INR and GBP, **when** I GET `/holdings`, **then** each shows `avgCost` and `lastPrice` in its **native** currency and `marketValue` in the **base** currency.
- **Given** a position sold to zero, **when** I GET `/holdings`, **then** it is absent by default and present with `?includeZero=true`, showing its realised P&L.
- **Given** weights across all positions, **when** they are summed, **then** they total 100 ± 0.01.

### PM-9 · Search for an instrument · **Must** · 5 pts · Dev A + C · **Day 2**
- **Given** I type "rel", **when** the type-ahead fires, **then** RELIANCE ranks above any name-only match, within 200 ms.
- **Given** I type a symbol that does not exist, **when** results return, **then** `[]` and the form shows "no match", not an error.

### PM-10 · See a live market price · **Must** · 8 pts · Dev B · **Day 2**
- **Given** an instrument in the catalogue, **when** I view holdings, **then** the price came from a real provider, not a fixture.
- **Given** the same instrument twice within 15 minutes, **when** I view holdings, **then** the provider was called once — the second read is a cache hit.
- **Given** the provider is unreachable, **when** I view holdings, **then** the most recent `price_history` row is used and `priceAsOf` shows the older date.
- **Given** an instrument with no stored price at all, **when** I view holdings, **then** the seeded price is used and nothing 500s.

### PM-11 · Prices survive an outage · **Must** · 8 pts · Dev B · **Day 3**
- **Given** the provider returns 429, **when** a refresh runs, **then** we back off, serve cached data, and no caller sees a 5xx.
- **Given** three consecutive provider failures, **when** a fourth request arrives, **then** the circuit is open and we do not call the provider for 5 minutes.
- **Given** the whole machine is offline, **when** the full demo runs, **then** every screen renders from seeded data.

### PM-12 · Browse in the UI · **Must** · 5 pts · Dev C · **Day 2–3**
- **Given** I am signed in, **when** I open the app, **then** my portfolios list, and clicking one shows its holdings.
- **Given** a slow API, **when** the page loads, **then** skeletons render, not a blank screen.

---

## 5. Epic 3 — View performance *(customer priority 2)*

### PM-13 · Daily performance series · **Must** · 13 pts · Dev A · **Day 3**
> As an investor, I want to see how my portfolio has moved over time, so that I can judge my decisions.

- **Given** a portfolio with transactions over six months, **when** I GET `/performance?from=&to=`, **then** I get one point per calendar day in range.
- **Given** a weekend or market holiday, **when** the series is built, **then** the previous close is carried forward and the point is flagged `"filled": true`.
- **Given** a date before my first transaction, **when** the series is built, **then** that date is **omitted**, not zero-filled.
- **Given** `from` after `to`, **when** I call it, **then** 400 with a field-level error.
- **Given** a range over 5 years, **when** I call it, **then** 400.
- **Given** 90 days across three currencies, **when** I call it, **then** it returns in under 300 ms.

### PM-14 · See the chart · **Must** · 8 pts · Dev C · **Day 3**
- **Given** a performance response, **when** the detail page loads, **then** a line chart renders total value over time.
- **Given** I hover a point, **when** the tooltip shows, **then** it gives the date and the value in the base currency.
- **Given** a portfolio with one transaction, **when** the chart renders, **then** it draws a line, not an error.
- **Given** an empty portfolio, **when** the chart renders, **then** it shows an empty state with a call to action.

### PM-15 · Separate market moves from deposits · **Should** · 5 pts · Dev A · **Day 3**
- **Given** I deposited ₹50,000 mid-period, **when** I read the summary, **then** `netContributions` reports it separately so `percentChange` is not flattered by my own money.

### PM-16 · Historical valuation · **Should** · 5 pts · Dev A + C · **Day 3**
- **Given** `asOf=2026-06-30`, **when** I GET `/valuation`, **then** I get the portfolio as it stood that day using that day's prices and that day's FX rate.
- **Given** an `asOf` before my first transaction, **when** I call it, **then** 400 with a clear message.

### PM-17 · Allocation breakdown · **Should** · 8 pts · Dev A + C · **Day 4**
- **Given** holdings across asset types, **when** I GET `/allocation?by=ASSET_TYPE`, **then** slices sum to 100 ± 0.01.
- **Given** `by=CURRENCY`, **when** I call it, **then** I can see my FX exposure as a percentage.
- **Given** cash in the portfolio, **when** allocation is computed, **then** cash is excluded from slices and reported separately.

### PM-18 · Portfolio analytics · **Could** · 5 pts · Dev A · **Day 5**
- **Given** a series, **when** I request analytics, **then** time-weighted return, annualised return, max drawdown and best/worst day are returned.
- **Given** a hand-computed spreadsheet fixture, **when** TWR is calculated, **then** it matches to 4 decimal places.

---

## 6. Epic 4 — Add items *(customer priority 3)*

### PM-19 · Record a BUY · **Must** · 8 pts · Dev A + C · **Day 3**
- **Given** a valid BUY, **when** I POST it, **then** 201 with `Location`, and my holding quantity increases by that amount.
- **Given** an existing holding, **when** I BUY more, **then** `avg_cost` becomes the weighted average of old and new cost **including fees**.
- **Given** quantity `0` or negative, **when** I POST, **then** 400 before anything is written.
- **Given** a symbol that does not exist, **when** I POST, **then** 404 and **no** `txn` row is created.
- **Given** a currency that is not the instrument's, **when** I POST, **then** 422 `/errors/currency-mismatch`.
- **Given** `executedAt` in the future, **when** I POST, **then** 400.
- **Given** a fractional quantity like `0.523100`, **when** I POST, **then** it is accepted at 6 dp.

### PM-20 · Record a SELL · **Must** · 8 pts · Dev A + C · **Day 3**
- **Given** I hold 20 AAPL, **when** I sell 50, **then** 422 `/errors/insufficient-quantity` and nothing is written.
- **Given** I hold 20, **when** I sell 5, **then** quantity becomes 15, `avg_cost` is **unchanged**, and `realised_pnl += (price − avgCost) × 5 − fees`.
- **Given** I hold 20, **when** I sell all 20, **then** the row stays at quantity 0 with its realised P&L preserved and disappears from the default holdings response.

### PM-21 · Cash transactions · **Should** · 5 pts · Dev A · **Day 3**
- **Given** a DEPOSIT, **when** it is recorded, **then** cash balance increases and no holding is created.
- **Given** the cash rule, **when** the balance is computed, **then** it equals DEPOSIT + SELL proceeds + DIVIDEND − WITHDRAWAL − BUY cost − FEE.

### PM-22 · Warned, not blocked, when buying beyond cash · **Should** · 5 pts · Dev A + C · **Day 3**
- **Given** ₹10,000 cash, **when** I buy ₹14,527 of stock, **then** 201 with `warnings[0]` naming the shortfall, and the transaction **is** recorded.
- **Given** sufficient cash, **when** I buy, **then** `warnings` is `[]`, not `null`.

### PM-23 · Add in the UI · **Must** · 5 pts · Dev C · **Day 3**
- **Given** the add form, **when** I search a symbol and submit, **then** the holding appears without a page reload.
- **Given** a 422 from the API, **when** it returns, **then** the form shows `ProblemDetail.detail` inline against the right field.

---

## 7. Epic 5 — Remove items *(customer priority 4)*

### PM-24 · Delete a transaction and rebuild · **Must** · 8 pts · Dev A · **Day 3**
> As an investor, I want to delete a mistaken transaction, so that my history is truthful.

- **Given** five transactions, **when** I delete the **first**, **then** holdings equal a from-scratch replay of the remaining four.
- **Given** a delete that would make a later SELL invalid, **when** I delete it, **then** 422 and nothing changes — the history must stay consistent.
- **Given** a delete, **when** it runs, **then** the txn removal and the projection rebuild are in one DB transaction; killing the process mid-way leaves neither applied.
- **Given** another user's transaction id, **when** I delete it, **then** 404 and their data is intact.

### PM-25 · Delete a portfolio · **Must** · 3 pts · Dev A + C · **Day 2**
- **Given** a portfolio with transactions and holdings, **when** I delete it, **then** 204 and all child rows go with it in one transaction.

### PM-26 · Remove in the UI · **Must** · 5 pts · Dev C · **Day 3**
- **Given** a transaction row, **when** I click delete, **then** I get a confirm dialog naming the transaction.
- **Given** I confirm, **when** the API succeeds, **then** the row disappears and holdings and chart both refresh.
- **Given** the API fails, **when** the optimistic delete rolls back, **then** the row returns and an error toast shows.

---

## 8. Epic 6 — Multi-currency *(customer-added, cross-cutting)*

### PM-27 · Hold instruments in several currencies · **Must** · 8 pts · Dev A · **Day 3**
- **Given** AAPL (USD), RELIANCE (INR) and SHEL (GBP) in one portfolio, **when** I value it in INR, **then** every position converts at the current rate and the total is correct to 4 dp.
- **Given** a transaction, **when** it is stored, **then** its price stays in the **instrument's** currency — never pre-converted.

### PM-28 · Real FX rates with a fallback · **Must** · 8 pts · Dev B · **Day 2–3**
- **Given** the FX provider is reachable, **when** rates are needed, **then** they are fetched, stored in `fx_rate` and cached for 6 hours.
- **Given** the provider is unreachable, **when** rates are needed, **then** the most recent stored rate is used and `rateAsOf` reflects its real date.
- **Given** no rate has ever been fetched, **when** rates are needed, **then** the seeded rates from `V12` are used.
- **Given** any code path, **when** it is reviewed, **then** no FX rate is hard-coded anywhere outside a test fixture.

### PM-29 · Change base currency without touching data · **Must** · 5 pts · Dev C · **Day 4**
- **Given** an INR portfolio, **when** I PATCH `baseCurrency` to USD, **then** every displayed number changes and **no** `txn` or `holding` row changes — asserted by table snapshot.
- **Given** the change, **when** I read holdings, **then** `avgCost` is still in each instrument's native currency.

### PM-30 · Cost basis at the trade-date rate · **Should** · 5 pts · Dev A · **Day 3**
- **Given** a USD buy made when USD/INR was 86.90, **when** cost basis is reported in INR, **then** it uses 86.90, not today's rate — so P&L contains the currency move as well as the market move.
- **Given** the trade date has no stored rate, **when** cost basis is computed, **then** the most recent rate on or before that date is used.

---

## 9. Epic 7 — Engineering quality *(assessed, not requested)*

| ID | Story | MoSCoW | Pts | Owner | Day |
|---|---|---|---|---|---|
| **PM-31** | One error shape everywhere — RFC 9457 + correlation ID, no stack traces, no SQL, no class names in any response | **Must** | 8 | C | 1 |
| **PM-32** | Testcontainers MySQL integration tests running in `mvn verify` and in the pipeline | **Must** | 5 | B | 4 |
| **PM-33** | JaCoCo 70 % line / 60 % branch, gate on, meaningful packages only | **Should** | 5 | A | 4 |
| **PM-34** | Multi-stage Dockerfile + compose bringing up MySQL and the API from cold | **Should** | 8 | B | 4 |
| **PM-35** | Jenkinsfile that a real push runs green on the VM | **Should** | 5 | B | 4 |
| **PM-36** | Concurrent writes to one holding never lose an update | **Must** | 3 | A | 4 |

**PM-31 acceptance:** given any of a 400, 401, 403, 404, 409, 422, 502 or a deliberately
thrown NPE, the response is `application/problem+json`, has a `correlationId` matching the
`X-Correlation-Id` header, and contains no `com.protify`, no `SQLException` and no `at ` line.

**PM-36 acceptance:** given two threads posting a BUY of 1 AAPL to the same portfolio
simultaneously, when both commit, then quantity is 2 — never 1.

---

## 10. Epic 8 — Showcase *(stretch)*

| ID | Story | MoSCoW | Pts | Owner | Day |
|---|---|---|---|---|---|
| **PM-37** | GraphQL read API, one round trip for the dashboard, same auth scoping as REST | **Could** | 8 | C | 5 |
| **PM-38** | FastAPI insights service returning an LLM narrative | **Could** | 8 | B | 5 |
| **PM-39** | Rule-based insights fallback when the LLM is absent, with `engine` visibly `RULE_BASED` | **Could** | 5 | B | 5 |
| **PM-40** | Natural-language query → validated structured filter (never generated SQL) | **Could** | 5 | B | 5 |
| **PM-41** | `portfolio_valuation_daily` materialisation so a 365-day series is under 100 ms | **Could** | 5 | A | 5 |
| **PM-42** | CSV export of transactions and holdings | **Could** | 2 | C | 5 |

---

## 11. Won't do this sprint — decided, not forgotten

| Not doing | Why | Where it is argued |
|---|---|---|
| Quantum portfolio optimiser | No customer requirement; a 4-asset QAOA toy is worse than a classical optimiser | ADR-0009 |
| FIFO / specific-lot cost basis | Weighted average matches `holding.avg_cost` and needs no lot table | ADR-0005 |
| Our own JWT + refresh-token exchange | Google's token is already signed, short-lived and validated for us | ADR-0004 |
| Spring Cloud gateway / Eureka / config server | One deployable, three developers | ADR-0003 |
| Corporate actions — splits, mergers | Real and hard; would silently corrupt quantities if done badly | ARCHITECTURE §9 |
| Intraday / streaming prices | Not on free tiers; daily close satisfies "view performance" | ARCHITECTURE §9 |
| Transaction **edit** (only create and delete) | Delete + re-add gives the same outcome through one code path | This file |
| Portfolio sharing, roles, teams | Not requested; per-user scoping is the whole requirement | — |
| Email or push notifications | No customer requirement | — |
| Benchmark comparison vs an index | Would be the first thing added in a Day 7 | — |

---

## 12. Sprint structure — six days

| Day | Sprint goal | Stories | Pts | Demo at 17:45 |
|---|---|---|---|---|
| **0** | Setup. No application code | — | — | Three green builds, one real Google token |
| **1** | **Identity and the error contract** | PM-1, PM-2, PM-3, PM-31 | 24 | Sign in with Google; a user row appears; a bad token gives a clean 401 |
| **2** | **Browse** | PM-5, PM-6, PM-7, PM-9, PM-10, PM-12, PM-25 | 37 | Create a portfolio; search RELIANCE; see a real INR price |
| **3** | **MVP — performance, add, remove** | PM-8, PM-11, PM-13, PM-14, PM-15, PM-16, PM-19, PM-20, PM-21, PM-22, PM-23, PM-24, PM-26, PM-27, PM-28, PM-30 | 101 | **The full loop in the browser, three currencies. Tag `v0.1-mvp`** |
| **4** | **Harden and containerise** | PM-4, PM-17, PM-29, PM-32, PM-33, PM-34, PM-35, PM-36 | 47 | The app from `docker compose up`, built green by Jenkins |
| **5** | **Showcase** | PM-18, PM-37 … PM-42 | 38 | GraphQL dashboard in one round trip; AI insights with the key removed |
| **6** | **Freeze, prove, present** | — | — | **The showcase**, rehearsed twice, with the wifi off |

Day 3 is deliberately overloaded — 101 points against a ~39-point day. That is not an
estimation error: Day 3 is where four epics converge, and the three developers work the same
stories in parallel rather than three separate ones. **If Day 3 is going to fail, it will be
visible by lunchtime**, which is exactly when `/docs/PLAN.md` §9 gets executed. Days 4 and 5
carry the slack to absorb it.

---

## 13. Definition of Ready

A story is not started until:

1. Its acceptance criteria are Given/When/Then, and each one names a test that will exist.
2. Its API shape is in `/docs/API_CONTRACT.md`, or it is the story that puts it there.
3. Its dependencies on the other two developers are named, and those tasks are merged or in review.
4. It fits in one day. A story that does not is split before it starts.

Definition of Done is in `/docs/DEFINITION_OF_DONE.md` and applies to every story above.
