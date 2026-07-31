# Day 4 — Dev C — Cross-user proof, base-currency switching, frontend completion

## You have no prior context. Read this whole file before doing anything.

You are **Dev C** on a three-person team building **Protify**, a Portfolio Management REST API
(Java 21, Spring Boot 3.5.16, MySQL 8.4, `NamedParameterJdbcTemplate` — **no JPA**) plus a
React frontend. Base package `com.protify.portfolio`.

**You own:** `portfolio-api` (controllers, `dto/`, `mapper/`, `error/`, `graphql/`), GitHub
Actions, Flyway `V20`–`V29`, and most frontend work.

**Status:** the MVP shipped on Day 3, tagged `v0.1-mvp`. Browse, performance, add and remove
all work in a browser, three currencies into one base currency, real Google auth, real prices.

> ## Today's first task is the most important test in the project
> `CrossUserAccessIT` is the test an assessor at an investment bank looks for **first**. Do it
> this morning, while you are fresh, not at 16:30.

**Read first:** `/docs/TEST_PLAN.md` §4.7 · `/docs/API_CONTRACT.md` §0.7, §5 ·
`/docs/DECISIONS/0011-multi-currency-valuation.md`.

---

## D4-C1 · `CrossUserAccessIT` — 1.5 h · 🔴 **the one that matters**

`portfolio-api/…/api/security/CrossUserAccessIT.java`

One **parameterised** test over every user-scoped endpoint. Set up user A and user B, each with
a portfolio and transactions, then have A attempt every operation against B's resources:

| Request as user A | Expected |
|---|---|
| `GET /portfolios/{B}` | 404 |
| `PATCH /portfolios/{B}` | 404 |
| `DELETE /portfolios/{B}` | 404 |
| `GET /portfolios/{B}/holdings` | 404 |
| `GET /portfolios/{B}/transactions` | 404 |
| `POST /portfolios/{B}/transactions` | 404 |
| `DELETE /portfolios/{B}/transactions/{txn}` | 404 |
| `GET /portfolios/{B}/valuation` | 404 |
| `GET /portfolios/{B}/performance` | 404 |
| `GET /portfolios/{B}/allocation` | 404 |

**404, never 403.** A 403 confirms the row exists, which leaks information. That distinction is
the point of the test and it is worth saying out loud in the presentation.

Two extra cases that a lazier version of this test would miss:

- **`shouldNotDeleteOtherUsersTransactionThroughOwnPortfolioPath`** — user A calls
  `DELETE /portfolios/{A}/transactions/{B's txn id}`. The path is legitimately A's; only the
  transaction id belongs to B. This catches a repository method scoped by portfolio but not by
  transaction ownership, which is the realistic version of this bug.
- After every attempt, **assert B's data is unchanged** — same row count, same quantities.

**Then prove the test works.** With Dev A, remove the `AND user_id = :userId` from
`PortfolioRepository.findByIdAndUser`, run the suite, confirm it goes **red**, put it back.
**Screenshot it** — it goes in the Day 6 presentation. A cross-user test that passes with the
guard removed proves nothing at all.

---

## D4-C4 · `PATCH /portfolios/{id}` — 1.0 h

`/docs/API_CONTRACT.md` §5. `UpdatePortfolioRequest` — `name` and `baseCurrency`, both
optional, at least one required.

**Changing `baseCurrency` must rewrite no `txn` and no `holding` row.** Stored values are in the
instrument's native currency; base currency is a presentation concern resolved at read time.
That is the customer's explicit requirement and ADR-0011's central claim.

**Test — `BaseCurrencyImmutabilityIT`:** snapshot the entire `txn` and `holding` tables, PATCH
the base currency from INR to USD, snapshot again, **assert zero diff**. Then assert every
money field in the valuation response changed and `avgCost` is still in each instrument's
native currency.

This test *is* the ADR. Without it, "presentation only" is a claim; with it, it is a fact.

---

## D4-C2 · Frontend — allocation, currency switcher, error states — 2.5 h · 🔴

`protify-frontend/src/**`

1. **Allocation pie chart** (Recharts `PieChart`) with a `by` selector: asset type, sector,
   currency. **Default it to `CURRENCY`** — it is the view that best shows the multi-currency
   feature, which is the most interesting thing the product does.
2. **Base-currency switcher** in the portfolio header. Changing it calls `PATCH`, refetches,
   and every number on the page re-renders. **This is the single best 10 seconds of the demo** —
   one dropdown, and a whole portfolio re-expresses itself while the underlying data is
   provably untouched.
3. **Empty states** everywhere: no portfolios, no holdings, no transactions, a chart with one
   point, a chart with zero points.
4. **Error toasts driven by `ProblemDetail.title`**, with `detail` in the body. Field-level
   `errors[]` render inline against the right form field.
5. **`stale: true` badge** when `dataQuality.stale` is set — an amber "prices as of 27 Jul"
   marker. Do not hide it. Showing honest degraded data is the visible payoff of the whole
   fallback architecture, and it is worth pointing at during the demo.

**Tests — Vitest:** the currency switcher re-renders all values; a `ProblemDetail` maps to the
right toast; the stale badge appears when the flag is set.

---

## D4-C3 · Polish — 1.5 h

Loading skeletons; optimistic delete with rollback on failure (the row returns and a toast
shows); mobile layout usable at 390 px.

---

## Rules

- **DTOs are `record`s** with Bean Validation. Persistence types never cross the controller boundary.
- **No business logic in a controller.**
- **All errors through `GlobalExceptionHandler`.**
- **No `double`, no `float`.** Money is `BigDecimal` in Java, a **string** in JSON. Never `parseFloat` an amount you will calculate with.
- **Another user's resource is 404, never 403.**
- `portfolio-common` is frozen. **You may not edit `pom.xml`.**
- Migrations only in `V20`–`V29`.

## Do not touch

`portfolio-core/**` (Dev A) · `portfolio-platform/**` (Dev B) · `portfolio-common/**` ·
any POM · migrations `V1`–`V19`.

---

## Done when

- [ ] `CrossUserAccessIT` covers **every** user-scoped endpoint, all returning **404**
- [ ] The delete-through-own-portfolio-path case is covered
- [ ] **The suite verified red with a `WHERE user_id` clause removed — screenshot taken**
- [ ] `PATCH /portfolios/{id}` works; `BaseCurrencyImmutabilityIT` proves zero data change
- [ ] Allocation pie renders, defaulting to `by=CURRENCY`
- [ ] The base-currency switcher re-renders every value on the page
- [ ] Stale-data badge appears when `dataQuality.stale` is true
- [ ] Every empty state handled
- [ ] `mvn clean verify` green; merged to `develop` by 17:30

## Hand-off

Post the cross-user screenshot in the channel — it is a presentation asset, not just a test run.

Tomorrow (Day 5) is the buffer. If everything above is green you build **GraphQL**: a read-only
API mirroring the REST reads, with the same security context and calling the **same services**,
so an authorisation bug cannot exist in one API and not the other. The SDL is already written in
`/docs/API_CONTRACT.md` §19.

**If anything from Days 1–4 is unfinished, tomorrow is for that instead** — every Day 5 story
is MoSCoW *Could* precisely so it can be dropped without a conversation. And per
`/docs/RISKS.md` R2: if GraphQL is half-built at 15:00 tomorrow, revert it. A merged
half-feature is worse than an ADR explaining the deferral.
