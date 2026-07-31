# ARCHITECTURE.md — Protify Portfolio Manager

Modular monolith in Java, one companion Python service, one React SPA.
Base package `com.protify.portfolio`. Read `/docs/PLAN.md` §2 first — it amends the
reference design, and this document reflects the amended version.

---

## 1. System context

```mermaid
graph TB
  subgraph Browser
    UI["React + Vite SPA<br/>protify-frontend"]
  end

  subgraph Google
    GID["Google Identity<br/>accounts.google.com"]
    JWKS["JWKS endpoint"]
  end

  subgraph "Protify backend — one JVM, one Maven module since Day 1"
    API["api/, graphql/<br/>REST + GraphQL"]
    PLAT["security/, user/, marketdata/, fx/<br/>config/"]
    CORE["instrument/, portfolio/, transaction/<br/>holding/, valuation/"]
    COMMON["common/<br/>ports · MoneyUtils · errors · enums"]
  end

  INS["portfolio-insights<br/>Python FastAPI + LLM"]
  MDP["Market data provider<br/>Yahoo chart / Twelve Data"]
  FXP["FX provider<br/>Frankfurter / ECB"]
  DB[("MySQL 8.4<br/>Flyway-managed")]
  PY["scripts/backfill_prices.py<br/>yfinance · offline, one-shot"]

  UI -->|"Bearer: Google ID token"| API
  UI -->|"sign in"| GID
  API --> PLAT
  API --> CORE
  CORE --> COMMON
  PLAT --> COMMON
  PLAT -->|"validate signature"| JWKS
  PLAT -->|"HTTP + Caffeine cache"| MDP
  PLAT -->|"HTTP + Caffeine cache"| FXP
  PLAT -->|"feature-flagged, 2s timeout"| INS
  CORE --> DB
  PLAT --> DB
  PY -->|"generates seed migrations"| DB

  style CORE fill:#1f6feb,color:#fff
  style COMMON fill:#6e7681,color:#fff
  style INS stroke-dasharray: 5 5
```

Everything outside the JVM box is optional at runtime. **Pull the network cable and the
product still works** — that constraint drives most of what follows.

---

## 2. Packages and the dependency rule

> **Amended Day 1.** Through Day 1 this was five Maven modules (`portfolio-common`,
> `portfolio-db`, `portfolio-core`, `portfolio-platform`, `portfolio-api`) with the arrows below
> enforced by the module graph — `core → platform` did not compile because the dependency did
> not exist. The team collapsed it to one module the same day (`/docs/DECISIONS/0012`,
> superseding this specific clause of ADR-0003). The package list and the *intended* dependency
> direction are unchanged; only the word "enforced" below is no longer true.

```mermaid
graph TD
  API[api, graphql] --> CORE["instrument, portfolio, transaction,<br/>holding, valuation"]
  API --> PLAT["config, security, user,<br/>marketdata, fx"]
  API --> COMMON[common]
  CORE -.->|should not, nothing stops it| PLAT
  CORE --> COMMON
  PLAT --> COMMON

  style COMMON fill:#6e7681,color:#fff
  style CORE stroke:#c00,stroke-width:2px
```

| Package(s) | Owner | Contains | Frozen? |
|---|---|---|---|
| `common/` | A | `MoneyUtils`, `Money`, enums, `DomainException` tree, **ports**: `MarketDataProvider`, `FxRateProvider` | **Yes, end of Day 1** |
| `support/` | A | `BaseRepository` | No |
| `db/migration/` (resources, not code) | shared | Flyway migrations only. Add-only, per-dev number ranges | No — grows daily |
| `instrument/`, `portfolio/`, `transaction/`, `holding/`, `valuation/` | A | domain services and repositories | No |
| `config/`, `security/`, `user/`, `marketdata/`, `fx/`, `insights/` | B | | No |
| `api/`, `graphql/` | C | controllers, dto, mapper, error, `PortfolioApplication` | No |

**The rule that still matters, now unenforced: `instrument`/`portfolio`/`transaction`/
`holding`/`valuation` code has no business importing `marketdata`, `fx`, or an HTTP client.**
Core needs prices and FX rates; it gets them through interfaces declared in `common` and
implemented alongside `marketdata`/`fx`, wired by Spring at startup. Until Day 1 a developer who
tried to call an HTTP client from the valuation engine got a compile error. Now they get a
green build and a review comment — the same discipline `/docs/DECISIONS/0003` rejected as a
starting design ("package-only boundaries... does not survive 17:00 on Day 5") is what this
codebase now runs on, by later, explicit choice. An ArchUnit rule (`ArchitectureTest`, originally
scheduled for Day 4) is the way to get a compiler-shaped guarantee back; until it exists, review
is the only check.

This is ports-and-adapters applied only where it earns its keep — at the two boundaries that
face the network. Everywhere else, a service calls a repository directly, because a project
this size does not need an interface per class.

---

## 3. Layering inside a package

```
Controller      HTTP, status codes, DTOs. No business logic. Never sees a domain entity.
    ↓  (Mapper — explicit, hand-written, unit-tested)
Service         Business rules, @Transactional boundaries, orchestration.
    ↓
Repository      NamedParameterJdbcTemplate + explicit SQL + hand-written RowMapper.
                No business logic. Every user-scoped query takes userId as an argument.
    ↓
MySQL
```

Enforced by ownership and review. Through Day 1 there was also a structural fact backing it up:
DTO records lived in `portfolio-api`, a module `portfolio-core` could not see, so a repository
type physically could not be returned from a controller — the dependency needed to do it did
not exist. That structural guarantee is gone now that everything is one module (`/docs/DECISIONS/0012`);
a repository type returned from a controller compiles fine today and is caught in review, not
by the build.

---

## 4. Read path — `GET /api/v1/portfolios/7/performance?from=2026-01-01&to=2026-07-30`

The hardest read in the product: three currencies, historical prices, historical FX.

```mermaid
sequenceDiagram
  autonumber
  participant UI as React SPA
  participant CF as CorrelationIdFilter
  participant BF as BearerTokenAuthenticationFilter
  participant JD as NimbusJwtDecoder + JWKS cache
  participant CUR as CurrentUserResolver
  participant PC as PerformanceController
  participant PS as PerformanceService
  participant PE as ProjectionEngine
  participant TR as TransactionRepository
  participant PHR as PriceHistoryRepository
  participant FXS as CachingFxRateService
  participant DB as MySQL
  participant GEH as GlobalExceptionHandler

  UI->>CF: GET /performance, Bearer eyJ...
  CF->>CF: mint correlationId → MDC
  CF->>BF: continue chain
  BF->>JD: decode + validate
  JD->>JD: JWKS from cache (12 h TTL)
  Note over JD: signature · iss=accounts.google.com · aud=CLIENT_ID · exp
  JD-->>BF: Jwt(sub, email, email_verified)
  BF->>CUR: resolve principal
  CUR->>DB: SELECT id FROM app_user WHERE google_sub = :sub
  DB-->>CUR: userId = 42
  CUR-->>PC: AuthenticatedUser(42)

  PC->>PC: validate from ≤ to, range ≤ 5 years
  PC->>PS: series(userId=42, portfolioId=7, from, to)

  PS->>DB: SELECT … FROM portfolio WHERE id=:id AND user_id=:userId
  Note over PS,DB: no row ⇒ PortfolioNotFoundException ⇒ 404.<br/>Another user's portfolio is indistinguishable from a missing one.
  DB-->>PS: base_currency = INR

  par three flat queries, no N+1
    PS->>TR: findByPortfolioOrderByExecutedAt(7)
    TR->>DB: SELECT … FROM txn WHERE portfolio_id=:id ORDER BY executed_at, id
  and
    PS->>PHR: findRange(instrumentIds, from, to)
    PHR->>DB: SELECT … FROM price_history WHERE instrument_id IN (…) AND price_date BETWEEN …
  and
    PS->>FXS: ratesForRange({USD,GBP} → INR, from, to)
    FXS->>FXS: Caffeine (6 h TTL)
    alt cache miss and network up
      FXS->>DB: upsert fetched rates into fx_rate
    else network down
      FXS->>DB: SELECT most recent rate ≤ date  (last-good fallback)
    end
  end

  PS->>PE: fold(txns, ProjectionContext(target=INR, fxLookup))
  Note over PE: pure function — no Spring, no DB, no clock.<br/>Walks txns and dates in one merge pass.
  loop each calendar day in range
    PE->>PE: apply txns dated ≤ D, then value each holding<br/>qty × price(D, forward-filled) × fx(D, forward-filled)
  end
  PE-->>PS: 212 daily points + priceAsOf + rateAsOf

  PS-->>PC: PerformanceSeries (domain type)
  PC->>PC: PerformanceMapper.toResponse(...)
  PC-->>UI: 200, amounts as JSON strings, currency INR, X-Correlation-Id echoed

  Note over GEH: any exception above unwinds here →<br/>ProblemDetail + correlationId, never a stack trace
```

Where each requirement is discharged on this path:

| Requirement | Step |
|---|---|
| Auth required | 3–7, before any controller code runs |
| User scoping | 10 — the `WHERE user_id` is in the SQL, not in a Java `if` |
| Another user's data | 11 — 404, never 403, so existence is not confirmed |
| No internet | 16 — last-good `fx_rate` row; same shape for `price_history` |
| Missing price | forward-fill inside the fold; `priceAsOf` exposed in the response |
| No `double` anywhere | `Money`/`BigDecimal` from the RowMapper to the JSON string |
| Correlation ID | 1 and 24 |

---

## 5. Write path — `POST /api/v1/portfolios/7/transactions`

```mermaid
sequenceDiagram
  autonumber
  participant UI as React SPA
  participant SEC as Security filter chain
  participant TC as TransactionController
  participant V as Bean Validation
  participant TS as TransactionService
  participant IR as InstrumentRepository
  participant TR as TransactionRepository
  participant PE as ProjectionEngine
  participant HR as HoldingRepository
  participant DB as MySQL
  participant GEH as GlobalExceptionHandler

  UI->>SEC: POST body {symbol:"RELIANCE", type:"BUY", quantity:"10", price:"1450.25", currency:"INR", executedAt:"2026-07-28T10:15:00Z"}
  SEC->>SEC: validate token → userId 42
  SEC->>TC: dispatch
  TC->>V: @Valid CreateTransactionRequest
  alt invalid — qty ≤ 0, unknown enum, future executedAt
    V-->>GEH: MethodArgumentNotValidException
    GEH-->>UI: 400 + errors[] {field, message}
  end
  TC->>TS: record(userId=42, portfolioId=7, cmd)

  rect rgb(238, 244, 255)
    Note over TS,DB: single @Transactional — everything here commits or none of it does
    TS->>DB: SELECT … FROM portfolio WHERE id=7 AND user_id=42 FOR UPDATE
    alt no row
      TS-->>GEH: PortfolioNotFoundException → 404
    end
    TS->>IR: findBySymbol("RELIANCE")
    alt unknown symbol
      TS-->>GEH: InstrumentNotFoundException → 404, before any write
    end
    TS->>TR: findByPortfolioOrderByExecutedAt(7)
    TR-->>TS: existing history
    TS->>PE: fold(history + newTxn, native context)
    alt SELL exceeds holding
      PE-->>TS: InsufficientQuantityException
      TS-->>GEH: 422 /errors/insufficient-quantity — nothing written, txn insert rolled back
    end
    PE-->>TS: new HoldingState map + cash balance
    TS->>TR: INSERT INTO txn … (KeyHolder → generated id)
    TS->>HR: INSERT INTO holding … ON DUPLICATE KEY UPDATE
    opt cash balance now negative
      TS->>TS: warnings += "Purchase exceeds available cash by ₹12,345.00"
    end
  end

  TS-->>TC: TransactionResult(id, warnings)
  TC-->>UI: 201, Location: /api/v1/portfolios/7/transactions/91, body incl. warnings[]
```

**Delete is the same picture with one difference.** `DELETE …/transactions/{txnId}` takes the
same `FOR UPDATE` lock, removes the row, then **replays the entire remaining history through
the same `ProjectionEngine`** and rewrites the projection. There is no separate reversal
code path, so there is nothing to drift out of sync — the mid-history-delete case and the
ordinary-append case are literally the same function call.

---

## 6. Currency model

Three currency concepts, kept strictly apart:

| Concept | Where it lives | Mutable? |
|---|---|---|
| **Native / trading currency** | `instrument.currency`, `txn.currency`, `holding.avg_cost` | Never |
| **Base currency** | `portfolio.base_currency` | Yes — user-editable |
| **Presentation currency** | `?currency=` query override | Per request |

```mermaid
graph LR
  T1["BUY 10 AAPL @ $180<br/>native USD"] --> H1["holding.avg_cost = 180.0000 USD"]
  T2["BUY 5 RELIANCE @ ₹1450<br/>native INR"] --> H2["holding.avg_cost = 1450.0000 INR"]
  H1 --> V["ValuationService<br/>base = INR"]
  H2 --> V
  FX["fx_rate<br/>USD pivot, daily"] --> V
  PX["price_history<br/>native currency"] --> V
  V --> R["PortfolioValuationResponse<br/>every amount in INR"]

  style V fill:#1f6feb,color:#fff
```

`positionValue(base) = quantity × price(native) × fx(native → base, on date)`

Rates are stored against a **USD pivot** — one row per currency per day, and a cross rate is
`rate(USD→quote) ÷ rate(USD→base)`. Storing pairs directly would be O(n²) rows for no gain.

Cost basis uses the FX rate **at each transaction's date**, not today's, so reported P&L
correctly contains both the market move and the currency move. This is why cost basis is
recomputed by replaying history rather than read from `holding` — see `/docs/PLAN.md` §5.

Changing a portfolio's base currency writes exactly one column on one row and touches no
`txn` or `holding`. `BaseCurrencyImmutabilityIT` asserts that.

---

## 7. Resilience — every external call, and what happens when it fails

```mermaid
graph LR
  R["Request needs a price"] --> C{"Caffeine<br/>15 min"}
  C -->|hit| OK["price + priceAsOf"]
  C -->|miss| CB{"Circuit<br/>breaker"}
  CB -->|open| DB1
  CB -->|closed| P["HTTP provider<br/>3s timeout, 1 retry, token bucket"]
  P -->|200| W["write price_history"] --> OK
  P -->|429 / 5xx / timeout| DB1[("price_history<br/>most recent row")]
  DB1 -->|found| STALE["price + older priceAsOf<br/>+ stale flag"]
  DB1 -->|nothing| SEED[("seeded rows from V11 —<br/>always present")]
  SEED --> OK

  style SEED fill:#2da44e,color:#fff
```

| External dependency | Primary | Fallback 1 | Fallback 2 | Never |
|---|---|---|---|---|
| Market prices | Yahoo chart / Twelve Data | Caffeine 15 min | `price_history`, then `V11` seed | throws to the caller |
| FX rates | Frankfurter (ECB) | Caffeine 6 h | `fx_rate` last-good, then `V12` seed | hard-coded rate |
| Google JWKS | Google | Spring's JWKS cache | — | a self-signed token is accepted |
| LLM insights | FastAPI + LLM | rule-based summary in Java | feature flag → 501 | blocks the page |

Google JWKS is the one dependency with no offline story, and that is correct: a token whose
signature cannot be verified must be rejected. The mitigation is operational, not
architectural — Spring caches the key set, and the demo signs in once at the start.

---

## 8. Extraction seams

The monolith is drawn so that each of these becomes a service by moving a directory and
adding a client behind an interface that already exists. Named now; **not built now**.

```mermaid
graph TB
  subgraph "Today — one JVM"
    A1[api] --> C1[core]
    A1 --> P1[platform]
  end
  subgraph "Seam 1 — market data service"
    MD["marketdata + fx<br/>already behind MarketDataProvider<br/>and FxRateProvider ports"]
  end
  subgraph "Seam 2 — valuation service"
    VS["valuation<br/>pure ProjectionEngine, no DB writes"]
  end
  subgraph "Seam 3 — BFF"
    GQ["graphql<br/>read-only, no shared state"]
  end
  subgraph "Already separate"
    IN["insights — Python FastAPI"]
  end
  P1 -.-> MD
  C1 -.-> VS
  A1 -.-> GQ
  P1 --> IN
```

| Seam | What makes it a seam today | Extraction cost | Trigger to actually do it |
|---|---|---|---|
| **1 · Market data + FX** | Already behind `MarketDataProvider` / `FxRateProvider` in `common`. No core code imports an HTTP client | ~1 day: new Spring Boot app, ports become a Feign/RestClient adapter, `price_history` and `fx_rate` move with it | Price refresh starts competing with request traffic, or a second product needs the same prices |
| **2 · Valuation** | `ProjectionEngine` is a pure function with no I/O. `ValuationService` performs no writes | ~2 days: needs a transaction-history feed (event stream or read replica) | Valuation CPU dominates, or back-testing arrives and needs to scale independently |
| **3 · GraphQL BFF** | `graphql/` shares only the security context with REST | ~0.5 day: it is already a separate package with its own resolvers | A second client (mobile) needs a different shape from the REST one |
| **4 · Insights** | Already a separate process, separate language, feature-flagged, with an in-process fallback | Zero — it is already extracted | Done |

Deliberately *not* seams: `portfolio`, `transaction` and `holding`. They share one
transactional invariant — the projection must be written in the same DB transaction as the
transaction row. Splitting them means distributed transactions or eventual consistency, and
we would be trading a `@Transactional` annotation for a saga. `/docs/DECISIONS/0003` argues
this out.

---

## 9. What we are deliberately not building

Stated plainly, because the gap between "did not think of it" and "decided against it" is
most of what gets assessed.

| Not building | Why | What we would do instead if asked |
|---|---|---|
| Spring Cloud Gateway / Eureka / Config Server | 3 developers, 6 days, one deployable. Service discovery for a single service is ceremony. Costs ~2 days and adds four failure modes | Extract along §8's seams when a second team or a second scaling profile exists |
| Kafka / event sourcing | We have the *benefit* of event sourcing already — `txn` is the log and `holding` is the projection — without the operational cost | Publish domain events from `TransactionService` at the existing seam |
| Our own JWT + refresh tokens | Google ID tokens are already signed, short-lived and validated by Spring. Writing our own gets us a worse version of a solved problem. See ADR-0004 | `POST /auth/session` exchange — designed in the ADR, not implemented |
| FIFO / specific-lot cost basis | Weighted average matches `holding.avg_cost` and needs no lot table. See ADR-0005 | `txn_lot` table + a lot-aware `ProjectionEngine` strategy; the engine takes it as a parameter already |
| Corporate actions (splits, mergers) | Real, hard, and not on the customer's priority list. A split would silently corrupt quantities | `corporate_action` table replayed by the projection engine — it is a fold, so this fits naturally |
| Intraday / streaming prices | Free tiers do not offer it, and a daily-close chart satisfies "view performance" | WebSocket provider behind the same `MarketDataProvider` port |
| Multi-tenancy beyond per-user scoping | `user_id` on every query is the requirement. Row-level security or schema-per-tenant is not | — |
| Quantum optimiser | Cut on Day 0. ADR-0009 | Classical mean-variance optimiser; strictly better and a third of the work |
| Soft deletes / audit trail | `txn` is already an append-mostly ledger. Full audit is a compliance requirement nobody stated | `txn_audit` trigger or an outbox |
| Horizontal scaling, k8s, HPA | One container, one instance, one instructor | The Dockerfile is already stateless; anything else is deployment config |

---

## 10. Cross-cutting concerns, and where each one lives

| Concern | Implementation | Package area (Dev) |
|---|---|---|
| Correlation ID | `CorrelationIdFilter` → MDC → response header → every `ProblemDetail` | security/web (B) |
| Authentication | Spring Security resource server, JWKS-validated | security (B) |
| Authorisation | `WHERE user_id = :userId` in every SQL statement. Not an annotation — a column | instrument/portfolio/transaction/holding/valuation (A) |
| Error translation | One `@RestControllerAdvice`, RFC 9457 | api (C) |
| Rounding | `MoneyUtils` only. Scale 4 money, 6 quantity, 8 FX, `HALF_UP` | common (A) |
| Time | UTC everywhere; `Clock` injected so tests can freeze it | common (A) |
| Caching | Caffeine, two caches with different TTLs, declared in one `CacheConfig` | config (B) |
| Transactions | `@Transactional` on service methods only, never on repositories | instrument/portfolio/transaction/holding/valuation (A) |
| Config | `application.yml` + profiles, every secret an env var with a local default | config (B) |
