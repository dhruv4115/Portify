# Day 1 — Dev A — `portfolio-common` and the baseline schema

## You have no prior context. Read this whole file before doing anything.

You are **Dev A** on a three-person team building **Protify**, a Portfolio Management REST API
(Java 21, Spring Boot 3.5.16, MySQL 8.4, `NamedParameterJdbcTemplate` — **no JPA**) plus a
React frontend. Base package `com.protify.portfolio`.

**You own:** all POMs, `portfolio-common`, `portfolio-core`, Flyway `V1`–`V9`.

> ## ⚠️ Today's hard deadline
> **`portfolio-common` freezes at the end of today.** Devs B and C spend all of Day 2
> compiling against what you merge tonight. `MoneyUtils`, the enums, the exception hierarchy
> and **both ports** must be on `develop` by 17:30. If the ports are not merged by **14:00**,
> stop everything else and finish them. This is `/docs/RISKS.md` R10 and it costs two
> developer-days if it slips.

**Read first:** `/CLAUDE.md` · `/docs/PLAN.md` §2, §3, §5 · `/docs/REFERENCE_DESIGN.md` §1–§3 ·
`/docs/DECISIONS/0001`, `0002`, `0008`.

---

## D1-A1 · `MoneyUtils` and `Money` — 1.5 h · 🔴

`portfolio-common/src/main/java/com/protify/portfolio/common/money/`

Every rounding decision in this codebase resolves here, and nowhere else.

```java
public record Money(BigDecimal amount, CurrencyCode currency) { … }

public final class MoneyUtils {
    public static final int MONEY_SCALE    = 4;
    public static final int QUANTITY_SCALE = 6;
    public static final int FX_SCALE       = 8;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    public static BigDecimal money(BigDecimal v);        // setScale(4, HALF_UP)
    public static BigDecimal quantity(BigDecimal v);     // setScale(6, HALF_UP)
    public static BigDecimal fxRate(BigDecimal v);       // setScale(8, HALF_UP)
    public static boolean isZero(BigDecimal v);          // compareTo(ZERO) == 0
    public static boolean isNegative(BigDecimal v);
    public static BigDecimal safeDivide(BigDecimal a, BigDecimal b, int scale);  // null if b is zero
    public static BigDecimal pctChange(BigDecimal from, BigDecimal to);          // null if from is zero
}
```

**`pctChange` returning `null` when the base is zero is a named customer requirement**
(REFERENCE_DESIGN §3, last-but-one row). Not `Infinity`, not `0`, not an exception. It shows up
in real life on an empty portfolio, a cash-only portfolio and a fully gifted position.

`Money` must reject a null currency at construction and must implement `equals` by
**value comparison** — `new BigDecimal("100.00")` and `new BigDecimal("100.0000")` are equal in
value but not under `BigDecimal.equals`. Normalise the scale in the compact constructor.

**Tests — `MoneyUtilsTest`, 12+ cases:** rounding at each scale, `HALF_UP` at exactly `.5`,
`isZero` on `0.0000` vs `0`, `safeDivide` by zero → `null`, `pctChange` from zero → `null`,
`pctChange` negative → negative, `Money` equality across scales, null currency rejected.

---

## D1-A2 · Enums — 0.75 h · 🔴

`portfolio-common/…/common/enums/`

```java
AssetType       { STOCK, ETF, MUTUAL_FUND, BOND, TREASURY, CASH, CRYPTO }
TransactionType { BUY, SELL, DIVIDEND, DEPOSIT, WITHDRAWAL, FEE }
CurrencyCode    { USD, EUR, GBP, INR }
PriceSource     { YAHOO, TWELVE_DATA, YFINANCE, MANUAL, SEED }
FxSource        { FRANKFURTER, MANUAL, SEED }
```

`AssetType` gains `MUTUAL_FUND` and `TREASURY`, and `PriceSource` gains `YAHOO`, over
REFERENCE_DESIGN §2 — the customer requires a broader asset model (`/docs/PLAN.md` §2.2, §2.3).
`MUTUAL_FUND` is 12 characters and the column is `VARCHAR(16)`, so no DDL change.

Every enum needs:

```java
public static Optional<AssetType> fromDbValue(String v);   // never throws
```

**Never call `valueOf` inside a `RowMapper`.** An unrecognised database value must produce a
logged warning and a handled absence, not an `IllegalArgumentException` from four frames deep
inside JDBC. This is REFERENCE_DESIGN §2's closing paragraph and it is a real requirement.

**Tests — `EnumMappingTest`:** every enum round-trips `name()`; every enum returns
`Optional.empty()` for `"NOT_A_VALUE"`, `null` and `""`.

---

## D1-A3 · The two ports — 0.75 h · 🔴 **the thing Dev B is waiting for**

`portfolio-common/…/common/port/`

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

public record PriceQuote(String symbol, LocalDate date, BigDecimal close,
                         CurrencyCode currency, PriceSource source) { }
public record FxQuote(CurrencyCode from, CurrencyCode to, LocalDate date,
                      BigDecimal rate, FxSource source) { }
```

These live in `common` so `portfolio-core` can call them **while being unable to import
`portfolio-platform`**, where the HTTP adapters live. That is the boundary the whole
architecture rests on (ADR-0003) — the valuation engine physically cannot reach an HTTP client.

Agree the signatures with Dev B **this morning**, before you write them. They are frozen tonight.

---

## D1-A4 · Exception hierarchy — 0.75 h · 🔴 **Dev C is waiting for this**

`portfolio-common/…/common/error/`

```java
public abstract class DomainException extends RuntimeException {
    public abstract String problemType();   // "/errors/insufficient-quantity"
    public abstract HttpStatus status();    // or an int, to keep common free of web types
}
```

Subclasses: `NotFoundException` (404) · `ValidationException` (400) ·
`InsufficientQuantityException` (422) · `CurrencyMismatchException` (422) ·
`UpstreamException` (502).

Each carries its own `problemType` slug so Dev C's `GlobalExceptionHandler` maps every one
**without a default branch**. Slugs must match `/docs/API_CONTRACT.md` §0.7 exactly.

`InsufficientQuantityException` carries `symbol`, `requested` and `held`, so the message can
read *"Cannot sell 50 of AAPL; holding is 20."* rather than something generic.

---

## D1-A5 · `V1__baseline.sql` — 1.0 h · 🔴

`portfolio-db/src/main/resources/db/migration/V1__baseline.sql`

Paste **exactly** the schema from `/docs/REFERENCE_DESIGN.md` §1: `app_user`, `instrument`,
`portfolio`, `txn`, `holding`, `price_history`, `portfolio_valuation_daily`. Do not improve it.
It is a frozen shared contract and Devs B and C are coding against these column names.

`TRANSACTION` and `POSITION` are reserved words in MySQL — that is why the tables are `txn` and
`holding`. Money is `DECIMAL(19,4)`, quantities `DECIMAL(19,6)`, timestamps `DATETIME(6)`
holding UTC. `utf8mb4` everywhere, never `utf8`.

**Test — `FlywayMigrationIT`** (Testcontainers `mysql:8.4`): all migrations apply to an
**empty** schema, no duplicate version numbers, and every money column is `DECIMAL(19,4)`
(query `information_schema.columns` — this catches a typo that would otherwise silently
truncate money).

---

## D1-A6 · `V2__seed_instruments.sql` — 0.75 h

18 instruments across **four currencies and six asset types**, proving the model is not
US-equity-only:

| Currency | Instruments |
|---|---|
| USD | AAPL, MSFT, NVDA, JPM · SPY, QQQ (ETF) · AGG (bond ETF) · US10Y (TREASURY) · BTC-USD (CRYPTO) |
| INR | RELIANCE, TCS, HDFCBANK · NIFTYBEES (ETF) · one MUTUAL_FUND |
| GBP | SHEL, HSBA · VUKE (ETF) |
| EUR | one STOCK, one ETF |

Include `exchange` and `sector` on every row — the allocation endpoint needs them on Day 4.
Symbols must match what `yfinance` uses, since Dev B backfills prices against them tomorrow:
`RELIANCE.NS`, `SHEL.L`. Store the **clean** symbol (`RELIANCE`) in `instrument.symbol` and
agree the provider-suffix mapping with Dev B — it belongs in their adapter, not your data.

**Test — `SeedDataIT`:** 18 rows, ≥ 4 distinct currencies, ≥ 6 distinct asset types.

---

## D1-A7 · `BaseRepository` — 1.0 h

`portfolio-core/…/core/support/BaseRepository.java`

Write the `KeyHolder` + `PreparedStatement.RETURN_GENERATED_KEYS` insert helper **once**.
REFERENCE_DESIGN §1 warns about this specifically: don't guess it, write it once, reuse it.
After today, no other class constructs a `KeyHolder`.

Also provide the `INSERT … ON DUPLICATE KEY UPDATE` upsert helper — the holdings projection
needs it tomorrow.

**Test — `BaseRepositoryIT`:** insert returns the generated id; upsert is idempotent under
repeat.

---

## Rules

- **No `double`, no `float`**, anywhere. `BigDecimal` only, compared with `compareTo`.
- **No JPA, no Hibernate, no Spring Data.** Explicit SQL, hand-written `RowMapper`s.
- No `JdbcTemplate` outside a `*Repository`.
- `portfolio-core` must not depend on `portfolio-platform`. Check with `mvn dependency:tree`.
- Every migration is **new** and in `V1`–`V9`. Never edit an applied one.

## Do not touch

`portfolio-platform/**` (Dev B) · `portfolio-api/**` (Dev C) · migrations `V10`+ · frontend.

---

## Done when

- [ ] `MoneyUtils` and `Money` merged, 12+ tests green, `pctChange` returns `null` on a zero base
- [ ] Five enums merged, each with a non-throwing `fromDbValue`
- [ ] **Both ports merged** — `MarketDataProvider`, `FxRateProvider`, `PriceQuote`, `FxQuote`
- [ ] Exception hierarchy merged, slugs matching `/docs/API_CONTRACT.md` §0.7
- [ ] `V1` and `V2` apply cleanly to an empty schema
- [ ] `BaseRepository` written once, with tests
- [ ] `mvn clean verify` green
- [ ] Merged to `develop` by 17:30
- [ ] **You have announced in the channel that `portfolio-common` is frozen**

## Hand-off

Tell Dev B: the ports are merged, here are the exact signatures.
Tell Dev C: the exception hierarchy is merged, here are the `problemType` slugs.

Tomorrow you build `ProjectionEngine` — the pure fold at the heart of the product
(`/docs/PLAN.md` §5). It has no dependencies beyond `common`, deliberately, so nothing can
block it. Two developers queue behind it, so it is your first task of the day, not your third.
