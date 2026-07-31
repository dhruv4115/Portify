# Day 2 — Dev A — Domain repositories and the `ProjectionEngine`

## You have no prior context. Read this whole file before doing anything.

You are **Dev A** on a three-person team building **Protify**, a Portfolio Management REST API
(Java 21, Spring Boot 3.5.16, MySQL 8.4, `NamedParameterJdbcTemplate` — **no JPA**) plus a
React frontend. Base package `com.protify.portfolio`.

**You own:** the POM, `common/` (**frozen since end of Day 1**), `support/`, `instrument/`, `portfolio/`, `transaction/`, `holding/`, `valuation/`,
Flyway `V1`–`V9`.

**Already merged (Day 1):** `MoneyUtils`, `Money`, five enums with `fromDbValue`, the
`DomainException` hierarchy, the `MarketDataProvider` / `FxRateProvider` ports,
`V1__baseline.sql`, `V2__seed_instruments.sql`, `BaseRepository`. Google sign-in works and
`GET /me` returns a user.

> ## Today's headline
> **`ProjectionEngine` (D2-A3) is your first task, not your third.** Dev C's controllers and
> Dev A's own Day-3 valuation both queue behind it. It has no dependencies beyond
> `common/`, deliberately, so nothing can block it.

**Read first:** `/CLAUDE.md` · `/docs/PLAN.md` §5 (**essential**) · `/docs/REFERENCE_DESIGN.md`
§3 · `/docs/DECISIONS/0002-transaction-centric-model.md` · `/docs/TEST_PLAN.md` §4.

---

## D2-A3 · `ProjectionEngine` — 2.5 h · 🔴 **DO THIS FIRST**

`holding/ProjectionEngine.java`, `HoldingState.java`, `ProjectionContext.java`

A **pure function**. No Spring, no annotations, no database, no `LocalDate.now()`.

```java
ProjectionResult project(List<Txn> orderedTxns, ProjectionContext ctx);
```

`ProjectionContext` carries the target currency and an FX lookup
`(CurrencyCode from, LocalDate on) -> BigDecimal`. Two call sites, and this is the whole design:

1. **Native run** — identity FX, each instrument in its own currency. Persisted to `holding`.
2. **Base-currency run** — target = the portfolio's base currency, FX at each transaction's
   date. Feeds valuation. **Never persisted.** This is what makes changing base currency
   provably safe (ADR-0011).

Per-instrument state is three numbers: `quantity`, `avgCost`, `realisedPnl`. Plus a portfolio
`cashBalance`.

**The rules — these are the tests (REFERENCE_DESIGN §3):**

| Event | Effect |
|---|---|
| `BUY` | `qty += q`; `avgCost = (oldQty×oldAvg + q×price + fees) / (oldQty + q)` — **fees capitalised** |
| `SELL` more than held | throw `InsufficientQuantityException` |
| `SELL` | `qty -= q`; **`avgCost` unchanged**; `realisedPnl += (price − avgCost)×q − fees` |
| `SELL` to zero | keep the state at quantity 0, preserving `realisedPnl` |
| `DIVIDEND` | cash increases; no effect on `avgCost` |
| `DEPOSIT` / `WITHDRAWAL` | cash only, no instrument |
| `FEE` | cash decreases |
| Cash | `DEPOSIT + SELL proceeds + DIVIDEND − WITHDRAWAL − BUY cost − FEE` |

Cash may go negative. That is allowed, and the caller turns it into a warning tomorrow
(`/docs/PLAN.md` §2.7) — the engine does not reject it.

**All arithmetic through `MoneyUtils`.** No inline `setScale`, no `RoundingMode` literal, no
`double` anywhere.

**Tests — `ProjectionEngineTest`, 18+ cases. This is the crown-jewel test class of the
project:**

1. single BUY sets quantity and avgCost including fees
2. second BUY at a different price produces the correct weighted average
3. SELL reduces quantity and leaves avgCost untouched
4. SELL computes realised P&L as `(price − avgCost)×qty − fees`
5. SELL to exactly zero keeps the state with realised P&L preserved
6. SELL of `held + 0.000001` throws
7. fractional quantities at 6 dp round-trip exactly
8. DEPOSIT, WITHDRAWAL, DIVIDEND, FEE each move cash correctly
9. BUY beyond cash **succeeds** and leaves cash negative
10. transactions out of chronological order are sorted before folding
11. two instruments stay independent
12. empty transaction list → empty result, zero cash, no exception
13. the same input twice produces an identical result (determinism)
14. base-currency run converts at each transaction's date, not today's
15. base-currency run of a single-currency portfolio equals the native run
16. an FX lookup returning empty for a date falls back to the most recent prior rate
17. avgCost stays in native currency even in a base-currency run
18. rounding: three BUYs whose average is a recurring decimal stays at scale 4

Build fixtures from a **hand-computed spreadsheet**, not from what the code returns. Otherwise
you only prove the code agrees with itself.

---

## D2-A1 · `PortfolioRepository` + `PortfolioService` — 1.5 h · 🔴

`portfolio/`

**Every method takes `userId` as its first parameter, and every SQL statement has
`WHERE user_id = :userId`.** Not sometimes — always. A repository method that *can* return
another user's row is a bug even if no controller currently calls it that way.

```java
List<Portfolio> findAllByUser(long userId);
Optional<Portfolio> findByIdAndUser(long userId, long portfolioId);
Portfolio create(long userId, String name, CurrencyCode baseCurrency);
void deleteByIdAndUser(long userId, long portfolioId);       // cascade children in one txn
Optional<Portfolio> lockForUpdate(long userId, long portfolioId);  // SELECT … FOR UPDATE
```

`lockForUpdate` is needed tomorrow for the write path. Write it now.

Duplicate name for the same user → 409. Catch the unique-constraint violation and translate it;
do not pre-check with a SELECT, which races.

**Tests — `PortfolioRepositoryIT`:** CRUD; duplicate name → conflict; **`shouldNotReturn
OtherUsersPortfolio`**; delete cascades to `txn` and `holding` in one transaction.

---

## D2-A2 · `InstrumentRepository` — 1.0 h · 🔴

`instrument/`

Not user-scoped — the instrument catalogue is shared.

```java
Optional<Instrument> findBySymbol(String symbol);       // case-insensitive, trimmed
List<Instrument> search(String query, AssetType type, CurrencyCode ccy, int limit);
```

Search ranks **symbol prefix above name substring**, limit ≤ 50. Use a `CASE` in `ORDER BY`;
do not sort in Java.

`findBySymbol` must be case-insensitive and trim whitespace — `" aapl "` resolves to `AAPL`.
Tomorrow's transaction endpoint depends on that.

**Tests — `InstrumentRepositoryIT`:** `"rel"` returns RELIANCE first; case-insensitive lookup;
unknown symbol → `Optional.empty()`; limit respected; filters compose.

---

## D2-A4 · `TransactionRepository` + `HoldingRepository` — 1.5 h · 🔴

`transaction/` and `holding/HoldingRepository.java`

```java
long insert(Txn txn);                                        // BaseRepository KeyHolder
List<Txn> findByPortfolioOrderByExecutedAt(long portfolioId);  // executed_at, then id
Page<Txn> findByPortfolioFiltered(long portfolioId, TxnFilter f, Pageable p);
void deleteByIdAndPortfolio(long txnId, long portfolioId);

void upsert(long portfolioId, HoldingState state);           // ON DUPLICATE KEY UPDATE
void deleteAllForPortfolio(long portfolioId);                // for the full rebuild
List<Holding> findByPortfolio(long portfolioId);
```

`findByPortfolioOrderByExecutedAt` must order by `executed_at` **then `id`** — two transactions
at the same instant must fold deterministically, or the projection is non-reproducible and
`ProjectionRebuildConsistencyIT` will fail on Day 6 in a way that is very hard to diagnose.

Hand-written `TxnRowMapper` and `HoldingRowMapper`. Use `fromDbValue` for every enum — never
`valueOf` inside a mapper.

**Tests:** `TransactionRepositoryIT` — insert returns an id; ordering is stable under equal
timestamps; filters and paging work. `HoldingRepositoryIT` — upsert inserts then updates;
idempotent under repeat; `deleteAllForPortfolio` clears exactly one portfolio.

---

## Rules

- **`common/` is frozen.** Need a change? Team agreement first, in the channel.
- No `double`, no `float`. `BigDecimal` compared with `compareTo`, never `equals`.
- All rounding through `MoneyUtils`.
- No JPA, no Hibernate, no Spring Data. Hand-written `RowMapper`s only.
- No `JdbcTemplate` outside a `*Repository`.
- No business logic in a repository — that includes "just this one `if`".
- Domain code (`instrument`/`portfolio`/`transaction`/`holding`/`valuation`) must not import
  `marketdata`/`fx`/`security`/etc. directly — only `MarketDataProvider`/`FxRateProvider` in
  `common`. No longer compile-enforced since ADR-0012; review it by eye.
- Migrations only in `V1`–`V9`, and only new ones.

## Do not touch

`config/`, `security/`, `user/`, `marketdata/`, `fx/`, `insights/` (Dev B) · `api/`, `graphql/` (Dev C) · migrations `V10`+ · frontend.

---

## Done when

- [ ] `ProjectionEngine` merged with 18+ passing tests, fixtures from a spreadsheet
- [ ] It is a pure function — no Spring, no DB, no clock. Verified by it having no annotations
- [ ] Every portfolio query is user-scoped, and a cross-user test proves it
- [ ] Instrument search ranks symbol prefix first, in SQL
- [ ] Transaction ordering is stable under equal timestamps
- [ ] Holding upsert is idempotent
- [ ] `mvn clean verify` green; merged to `develop` by 17:30

## Hand-off

**Tell Dev C the moment `ProjectionEngine` and `PortfolioService` are on `develop`** — their
controllers are blocked until then, so merge those two before your repositories if you have to
choose.

Tomorrow is the MVP day and you carry its heaviest load: `TransactionService` (write + delete
with full rebuild), `ValuationService` (base-currency run with FX), and `PerformanceService`
(the daily series). Read `/docs/DECISIONS/0010-performance-series-in-java.md` tonight — the
series is a three-query-plus-fold, not SQL, and that is the decision that makes tomorrow
achievable.
