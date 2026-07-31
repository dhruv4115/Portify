# Day 4 — Dev A — Edge cases, concurrency, the coverage gate, allocation

## You have no prior context. Read this whole file before doing anything.

You are **Dev A** on a three-person team building **Protify**, a Portfolio Management REST API
(Java 21, Spring Boot 3.5.16, MySQL 8.4, `NamedParameterJdbcTemplate` — **no JPA**) plus a
React frontend. Base package `com.protify.portfolio`.

**You own:** the POM, `common/` (frozen), `support/`, `instrument/`, `portfolio/`, `transaction/`, `holding/`, `valuation/`, Flyway `V1`–`V9`.

**Status:** the MVP shipped last night and is tagged `v0.1-mvp`. Browse, view performance, add
and remove all work end to end in a browser across three currencies, with real Google auth and
real prices.

**Today is about making it true rather than merely working.** The edge cases you close today
are the ones the assessor will probe.

**Read first:** `/docs/TEST_PLAN.md` §4 (all ten cases) and §3 (coverage) ·
`/docs/DECISIONS/0002-transaction-centric-model.md`.

> **If any Day 3 task slipped, finish it before starting here.** Day 5 is the buffer; today
> is not.

---

## D4-A1 · Close every edge case in `/docs/TEST_PLAN.md` §4 — 2.5 h · 🔴

Work through the ten named cases and make each one green. Several are already covered from
Day 3; the ones most likely still missing:

**Sell exceeding holding (§4.1)** — boundary cases specifically: selling *exactly* the held
quantity succeeds and leaves 0; selling `held + 0.000001` fails. Fractional boundary: hold
`0.523100`, sell `0.523100` → ok, sell `0.523101` → 422.

**Zero cost basis (§4.2)** — assert the JSON field is literally `null`, not absent, not
`"NaN"`, not `0`. Same rule in `PerformanceSummary.percentChange` when `startValue` is zero.

**Missing price (§4.3)** — the important negative: **assert we never forward-fill from a
*future* price.** A naive `NavigableMap` lookup that uses `ceilingEntry` instead of
`floorEntry` passes every optimistic test and silently invents history.

**Deleted mid-history transaction (§4.5)** — you have the basic case; add the rollback case
(delete inside a transaction that then throws → neither the delete nor the rebuild persisted).

**Currency mismatch (§4.6)** — add `shouldAcceptMixedCurrencyPortfolio`, the *positive* case.
Without it, an over-eager rejection rule could pass every negative test while breaking the
customer's headline feature.

**Empty portfolio (§4.9)** and **unknown symbol (§4.10)** — `" aapl "` with whitespace and
mixed case must resolve to `AAPL`; `?query=zzzzz` returns `200 []`, because a search finding
nothing is not an error.

**Also add the ArchUnit rules** (`/docs/TEST_PLAN.md` §5) — they take 30 minutes and they
enforce mechanically what would otherwise be review discipline:

```java
noClassesInCore().shouldDependOn("..platform..");     // the module boundary
noClasses().that().resideOutsideOfPackage("..repository..")
           .should().dependOn(JdbcTemplate.class);
noMethods().should().haveRawParameterTypes(double.class, float.class);  // in core
controllers().should().notReturnDomainTypes();
```

---

## D4-A2 · `ConcurrentWriteIT` — 1.5 h · 🔴

`transaction/ConcurrentWriteIT.java`

Two threads, a `CountDownLatch`, both POSTing a BUY of 1 AAPL to the same portfolio.
**Assert the final quantity is 2, never 1.**

This test must **not** be `@Transactional` — it needs real commits. Clean up explicitly.

**Then prove it is a real test:** remove the `SELECT … FOR UPDATE` from
`PortfolioRepository.lockForUpdate`, run it, and confirm it fails. Put the lock back. A
concurrency test that passes without the lock proves nothing, and this one takes ten minutes to
verify.

Also cover: a concurrent BUY and a DELETE of a different transaction leave the projection
consistent with whichever committed last (both orderings are legal; an inconsistent third state
is not), and no deadlock — both threads finish inside 5 seconds. Lock ordering is always
`portfolio`, then `txn`, then `holding`.

---

## D4-A3 · JaCoCo coverage gate — 1.0 h

You own `pom.xml`, so this is yours.

- **70 % line, 60 % branch**, build fails below.
- **90 % line** on `ProjectionEngine`, `MoneyUtils`, `ValuationService`, `PerformanceService`, `FxRateService` — these are where the money logic lives and they are pure functions, so it is cheap.
- Exclude, per `/docs/TEST_PLAN.md` §3: `**/dto/**`, `**/*Response`, `**/*Request`, `**/config/**`, `PortfolioApplication`, `**/*RowMapper` (covered transitively by repository ITs), generated GraphQL types.

The gate goes on **today**, not on Day 1 — a coverage gate imposed early only teaches people to
write assertion-free tests.

If the build goes red, **write real tests rather than adjusting the threshold.** If a genuine
exclusion is missing, add it and note why in the POM comment.

---

## D4-A4 · `AllocationService` — 1.5 h

`valuation/AllocationService.java`

Breakdown by `ASSET_TYPE`, `SECTOR`, `CURRENCY`, `INSTRUMENT` — all in base currency, reusing
the base-currency projection you already built.

- Weights sum to 100 ± 0.01; put the rounding residual on the **largest** slice, so the total is exact and the error lands where it is least visible.
- **Cash is excluded** from slices and reported separately.
- `by=CURRENCY` is the one that best demonstrates the multi-currency feature — it shows FX exposure as a percentage. Make sure that one is right.

**Tests — `AllocationServiceTest`:** weights sum to 100 for each `by` value; empty portfolio →
empty slices, not a divide-by-zero; a single holding → one slice at 100 %; cash excluded;
`by=CURRENCY` on a three-currency portfolio correct.

---

## Rules

- **`common/` is frozen.**
- Domain code must not import `marketdata`/`fx` directly. This was going to be a *second*,
  redundant check on top of the Maven module graph — ADR-0012 (Day 1) removed the module graph,
  so your ArchUnit rule is now the only automated enforcement left. Worth doing early today, not
  late.
- No `double`, no `float`. `compareTo`, never `equals`. Rounding through `MoneyUtils`.
- No JPA, no Hibernate, no Spring Data. No `JdbcTemplate` outside a `*Repository`.
- Migrations only in `V1`–`V9`.
- **Do not lower a coverage threshold to make the build pass.**

## Do not touch

`config/`, `security/`, `user/`, `marketdata/`, `fx/`, `insights/` (Dev B) · `api/`, `graphql/` (Dev C) · migrations `V10`+ · frontend.

---

## Done when

- [ ] All ten edge cases in `/docs/TEST_PLAN.md` §4 are green
- [ ] We provably never forward-fill from a future price
- [ ] `shouldAcceptMixedCurrencyPortfolio` exists — the positive case, not only the negative
- [ ] `ConcurrentWriteIT` green, and **verified red with the `FOR UPDATE` removed**
- [ ] ArchUnit rules in place and passing
- [ ] JaCoCo gate on at 70/60, 90 on the money classes, build fails below
- [ ] Allocation weights sum to 100 ± 0.01 for all four `by` values
- [ ] `mvn clean verify` green; merged to `develop` by 17:30

## Hand-off

Help Dev C with `CrossUserAccessIT` today — specifically the **mutation check**: remove the
`AND user_id = :userId` from one of your repository queries, confirm their suite goes red, put
it back, and **screenshot it**. A cross-user test that passes with the guard removed is
worthless, and that screenshot belongs in the Day 6 presentation.

Tomorrow (Day 5) is the buffer. If everything above is green, you build analytics — TWR,
annualised return, max drawdown — and the `portfolio_valuation_daily` materialisation. **If
anything on Days 1–4 is unfinished, tomorrow is for that instead**, and all of Day 5's stories
are MoSCoW *Could* precisely so they can be dropped without a conversation.
