# TEST_PLAN.md — Protify Portfolio Manager

The test suite has one job at assessment: **prove the money is right and prove user A cannot
see user B's data.** Everything else is supporting evidence.

---

## 1. The pyramid

```
                    ╱ Manual demo dry-run ╲            1 run, Day 6, wifi off
                  ╱   E2E — 4 UI journeys   ╲          ~4,  minutes
                ╱  Integration (*IT) — 45     ╲        Testcontainers MySQL, ~90 s
              ╱  Web slice (@WebMvcTest) — 80   ╲      MockMvc, no DB, ~15 s
            ╱      Unit — 220                     ╲    pure JUnit + Mockito, ~8 s
```

| Level | Count | Runs in | Speed | Owns |
|---|---:|---|---|---|
| **Unit** | ~220 | `mvn test` | < 10 s | `ProjectionEngine`, `MoneyUtils`, valuation maths, mappers, FX conversion |
| **Web slice** | ~80 | `mvn test` | < 20 s | status codes, validation, JSON shape, security rules — services mocked |
| **Integration `*IT`** | ~45 | `mvn verify` | < 2 min | real SQL against real MySQL, migrations, transactions, locking, cross-user |
| **E2E** | 4 | manual + Vitest | minutes | the customer's four verbs through the browser |

**Ratio target ≈ 60 / 22 / 12 / 1.** If integration tests start outnumbering unit tests, the
business logic has leaked out of `ProjectionEngine` and into SQL — treat that as a design
smell, not a testing preference.

The pyramid is this shape because of one deliberate design choice: the hardest logic in the
product (`ProjectionEngine`, `ValuationService`, `PerformanceService`) is a **pure function
with no Spring, no database and no clock**. That is what makes 220 fast unit tests possible
and what makes the coverage target cheap to hit. See `/docs/PLAN.md` §5.

---

## 2. Naming and layout

```
portfolio-core/src/test/java/com/protify/portfolio/holding/ProjectionEngineTest.java
portfolio-core/src/test/java/com/protify/portfolio/holding/HoldingRepositoryIT.java
```

| Rule | Example |
|---|---|
| Test class mirrors the class under test | `TransactionService` → `TransactionServiceTest` |
| `*IT` means integration; runs in `verify` via failsafe | `HoldingRepositoryIT` |
| Method names read as sentences, no `test` prefix | `shouldRejectSellWhenQuantityExceedsHolding` |
| `should…When…` for behaviour, `shouldNot…When…` for negatives | `shouldNotCreateSecondUserWhenSubAlreadyExists` |
| `@DisplayName` only when the method name genuinely cannot carry it | `@DisplayName("P&L % is null, not Infinity, when cost basis is 0")` |
| `@Nested` per behaviour group inside big classes | `class WhenSelling { … }` |
| One assertion **concept** per test; AssertJ `assertThat`, never JUnit `assertEquals` | |
| Money asserted with `usingComparator(BigDecimal::compareTo)` — **never** `isEqualTo` | `100.00` and `100.0000` are equal in value, unequal in `equals` |

**Fixtures.** One `TestFixtures` class per module with builders — `aPortfolio()`,
`aBuy("AAPL", "10", "214.50")`. No fixture may hard-code an FX rate outside a test that is
specifically about FX.

---

## 3. Coverage

| Metric | Target | Gate |
|---|---|---|
| Line | **70 %** | build fails below |
| Branch | **60 %** | build fails below |
| `ProjectionEngine`, `MoneyUtils`, `ValuationService`, `PerformanceService`, `FxRateService` | **90 % line** | build fails below |

Gate switches on in `D4-A3`, not before — a coverage gate on Day 1 only teaches people to
write assertion-free tests.

**Excluded, and why each exclusion is legitimate:**

| Excluded | Reason |
|---|---|
| `**/dto/**`, `**/*Response`, `**/*Request` | records with no logic; the mappers that build them are tested |
| `**/config/**` | Spring wiring; proven by the context loading at all |
| `PortfolioApplication` | a `main` method |
| `**/*RowMapper` | covered transitively by every repository IT against real MySQL |
| generated GraphQL types | not our code |

Coverage is a floor, not a goal. A PR that raises coverage by testing getters and lowers it
by deleting a real test gets rejected.

---

## 4. The ten edge cases — every one has a named owner and a named test

These are the cases the assessor will look for. Each is a **specific test method**, not a
theme.

### 4.1 Sell exceeding holding
`ProjectionEngineTest#shouldRejectSellWhenQuantityExceedsHolding` · `TransactionControllerTest#shouldReturn422WhenSellExceedsHolding` · Dev A/C · Day 3

- Hold 20 AAPL, sell 50 → `InsufficientQuantityException`, `422`, type `/errors/insufficient-quantity`.
- Assert **nothing was written**: `txn` count unchanged, `holding.quantity` still 20 (`TransactionServiceIT`).
- Boundary: selling **exactly** 20 succeeds and leaves quantity 0. Selling `20.000001` fails.
- Fractional boundary: hold `0.523100`, sell `0.523100` → ok; sell `0.523101` → 422.

### 4.2 Zero cost basis → percentage is `null`
`MoneyUtilsTest#shouldReturnNullPercentWhenBaseIsZero` · `ValuationServiceTest#shouldReturnNullPnlPercentWhenCostBasisIsZero` · Dev A · Day 3

- Cost basis `0`, market value `500` → `unrealisedPnlPct` is **`null`**. Not `Infinity`, not `0`, not a 500.
- JSON assertion that the field is literally `null`, not absent and not `"NaN"`.
- Occurs in reality on: an empty portfolio, a fully gifted position, and a portfolio holding only cash.
- Same rule in `PerformanceSummary.percentChange` when `startValue` is 0.

### 4.3 Missing price
`ValuationServiceTest#shouldFallBackToLatestStoredPriceWhenNoPriceForDate` · Dev A/B · Day 3

- Instrument has no price for the requested date → use the most recent row **on or before** it; `priceAsOf` reports that older date.
- No stored price at all → the `V11` seed row is used.
- Genuinely nothing anywhere → the holding returns `marketValue: null` with `dataQuality.stale: true`. **The endpoint still returns 200** — one unpriceable instrument must not blank the portfolio.
- Assert we never forward-fill from a **future** price, which would silently invent history.

### 4.4 Stale price
`CachingMarketDataServiceTest#shouldServeStalePriceAndFlagItWhenProviderUnavailable` · Dev B · Day 3

- Provider throws → cached value returned, `stale: true` when `priceAsOf` is > 3 days behind.
- Provider returns 429 → same path, no exception escapes, no 5xx reaches the caller.
- Circuit opens after 3 consecutive failures and stays open 5 minutes; `RateLimitedProviderTest` asserts the provider is not called during that window.
- `HealthIndicatorTest`: health is **UP with `marketdata: DEGRADED`**, never DOWN. A stale price is not an outage.

### 4.5 A deleted mid-history transaction
`MidHistoryDeleteIT#shouldRebuildProjectionIdenticallyWhenOldestTransactionDeleted` · Dev A · Day 3 · **the assessor's favourite**

- Five transactions: BUY 10, BUY 5, SELL 3, BUY 2, SELL 4. Delete #1.
- Assert the resulting `holding` row is **byte-identical** to inserting the remaining four into an empty portfolio. Same quantity, same `avg_cost`, same `realised_pnl`.
- Delete a transaction that makes a **later SELL invalid** (delete the BUY 10 and the SELL 4 no longer has cover) → `422`, and assert the whole thing rolled back.
- Delete inside a `@Transactional` test that then throws → assert neither the delete nor the rebuild persisted.
- `ProjectionRebuildConsistencyIT` (Day 6): rebuild **every** portfolio's projection from `txn` and assert zero diff against the live table. This is the proof that the projection is genuinely derived and not drifting.

### 4.6 Currency mismatch
`TransactionServiceTest#shouldRejectTransactionWhenCurrencyDoesNotMatchInstrument` · Dev A · Day 3

- AAPL (USD) with a transaction in INR → `422 /errors/currency-mismatch`, field error on `currency`.
- A **portfolio** may legitimately mix currencies (that is the whole feature) — the constraint is per instrument, not per portfolio. `shouldAcceptMixedCurrencyPortfolio` asserts the positive case so the negative test cannot over-reach.
- LSE GBX normalisation: `PriceNormalisationTest#shouldConvertGbxToGbpOnIngestion` — a provider returning `2915` pence stores `29.1500 GBP`. Getting this wrong makes a portfolio look 100× too valuable.
- FX conversion round trip: USD → INR → USD returns the original within 0.0001.

### 4.7 Cross-user access
`CrossUserAccessIT` · Dev C · Day 4 · **the test an assessor looks for first**

Parameterised over **every** user-scoped endpoint:

| | |
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
| `POST /graphql { portfolio(id: B) }` | `data.portfolio: null` + `NOT_FOUND` |

- **404, never 403** — a 403 confirms the row exists.
- `shouldNotLeakExistenceThroughTiming`: the 404 for another user's portfolio and for a genuinely absent id take comparable time.
- `shouldNotDeleteOtherUsersTransactionThroughOwnPortfolioPath`: user A calls `DELETE /portfolios/{A}/transactions/{B's txn id}` → 404 and B's row survives.
- **Mutation test:** delete the `AND user_id = :userId` clause from `PortfolioRepository` and confirm this suite goes red. A cross-user test that passes with the guard removed is worthless. Do this once, by hand, on Day 4, and screenshot it for the presentation.

### 4.8 Concurrent writes to the same holding
`ConcurrentWriteIT#shouldNotLoseUpdateWhenTwoBuysCommitSimultaneously` · Dev A · Day 4

- Two threads, `CountDownLatch`, both POST BUY 1 AAPL to the same portfolio.
- Assert final quantity is **2**, never 1. Without the `SELECT … FOR UPDATE` on `portfolio` this fails — verify it fails when the lock is removed.
- Concurrent BUY and DELETE of a different transaction → the projection is consistent with whichever order committed; both outcomes are legal, an inconsistent third is not.
- Assert no deadlock: both threads finish within 5 seconds. Lock ordering is always `portfolio` first, then `txn`, then `holding`.

### 4.9 Empty portfolio
`ValuationServiceTest#shouldReturnZeroValuationForEmptyPortfolio` · Dev A/C · Day 2

- New portfolio → `200` with every money field `0.0000`, `holdingCount: 0`, `unrealisedPnlPct: null`. **Never 404, never 500.**
- `GET /holdings` → `[]`, not `null` and not 404.
- `GET /performance` → `points: []` with a valid `summary`, not a divide-by-zero.
- Frontend: an empty state with "Add your first transaction", not a blank chart axis.

### 4.10 Unknown symbol
`TransactionServiceTest#shouldRejectUnknownSymbolBeforeAnyWrite` · Dev A · Day 3

- POST with `"TSLAA"` → `404 /errors/instrument-not-found`, and `txn` count is **unchanged** — the lookup happens before the insert.
- `GET /instruments/TSLAA/prices` → 404.
- `GET /instruments?query=zzzzz` → `200 []`. A search finding nothing is not an error; a transaction against nothing is.
- Case-insensitivity: `"aapl"` resolves to `AAPL`. Whitespace `" AAPL "` is trimmed and resolves.
- The 404 body names the symbol but exposes no SQL and no class name.

---

## 5. Additional cases worth their place

| Test | Why it exists |
|---|---|
| `MoneyUtilsTest#shouldNeverUseDoubleArithmetic` — ArchUnit rule banning `double`/`float` in `core` | The rule that is easiest to break accidentally and hardest to spot in review |
| `ArchitectureTest#coreMustNotDependOnPlatform` | Enforces the module boundary that the whole seam story rests on |
| `ArchitectureTest#jdbcTemplateOnlyInRepositories` | Enforces the layering rule mechanically |
| `ArchitectureTest#controllersMustNotReturnDomainTypes` | Stops persistence types leaking through the API |
| `FlywayMigrationIT#shouldApplyAllMigrationsToEmptySchema` | Catches a broken migration before it reaches anyone's machine |
| `FlywayMigrationIT#shouldHaveNoOutOfOrderOrDuplicateVersions` | Catches two developers using the same version number |
| `GlobalExceptionHandlerTest#shouldNotLeakInternalsOnUnhandledException` | Throws a deliberate NPE and asserts the body has no `com.protify`, no `at `, no `SQL` |
| `JitProvisioningIT#shouldCreateExactlyOneUserUnderConcurrentFirstSignIn` | Two simultaneous first requests, one row |
| `BaseCurrencyImmutabilityIT` | Snapshots `txn` and `holding`, changes base currency, asserts zero diff |
| `PerformanceBudgetIT` | 500 transactions, 365 days, 3 currencies → series under 500 ms. Guards the one endpoint likely to get slow |
| `OfflineDemoIT` | Every provider bean replaced with one that always throws; asserts every read endpoint still returns 200 |

`OfflineDemoIT` deserves the emphasis: it is the automated version of the Day 6 wifi-off
dry-run, and it is the only test that directly protects the demo.

---

## 6. Integration test infrastructure

```java
@Testcontainers
abstract class AbstractIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withUrlParam("serverTimezone", "UTC")
            .withReuse(true);
}
```

- **One container per suite**, reused across classes. Starting MySQL per class costs minutes.
- Flyway runs the real migrations — never `ddl-auto`, which does not exist here anyway.
- Each test is `@Transactional` with rollback, **except** `ConcurrentWriteIT`, which needs real commits and cleans up explicitly.
- `serverTimezone=UTC` on the container URL, or `DATETIME` values shift and every date assertion becomes flaky in a way that looks like a logic bug.
- `mvn clean verify -DskipITs` skips this layer for a developer without Docker. **It is a convenience, not the definition of green** — CI runs the full set, and CI is the authority.

External providers are **never** called from a test. `MockRestServiceServer` for the market
data and FX adapters; a stub `MarketDataProvider` bean everywhere else. A test suite whose
result depends on Yahoo being up is not a test suite.

---

## 7. Frontend testing

Deliberately thin — the money logic is all server-side, and UI tests bought at the cost of
backend tests are a bad trade in six days.

| Level | Tool | What |
|---|---|---|
| Component | Vitest + Testing Library | `PerformanceChart` renders with 1 point, 0 points, 200 points |
| Component | Vitest | Add-transaction form maps a 422 `ProblemDetail` to the right inline field error |
| Component | Vitest | Money formatter renders `"18654.7500"` as `₹18,654.75` and never loses precision |
| Manual | — | The four customer journeys, run before every evening demo |

---

## 8. What runs where

| Suite | Local `mvn test` | Local `mvn verify` | GitHub Actions | Jenkins |
|---|:--:|:--:|:--:|:--:|
| Unit | ✅ | ✅ | ✅ | ✅ |
| Web slice | ✅ | ✅ | ✅ | ✅ |
| `*IT` Testcontainers | — | ✅ (needs Docker) | ✅ | ✅ |
| ArchUnit | ✅ | ✅ | ✅ | ✅ |
| JaCoCo gate | — | ✅ | ✅ | ✅ |
| Frontend Vitest | — | — | ✅ | ✅ |
| Offline dry-run | — | — | — | manual, Day 6 |

**Green means `mvn clean verify` passing with integration tests included.** A developer
without Docker Desktop can work all day on `-DskipITs`, but they do not get to call a branch
green — the pipeline does that.

---

## 9. Bug protocol

1. Reproduce it in a **failing test first**, at the lowest level that reproduces it.
2. Fix it.
3. The test stays. Name it for the bug: `shouldNotDoubleCountFeesOnWeightedAverage`.

A bug fixed without a test is a bug scheduled to return, and on a six-day project there is
no time to fix anything twice.
