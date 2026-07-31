# API_CONTRACT.md — Protify Portfolio Manager

Expands `/docs/REFERENCE_DESIGN.md` §4 into implementable detail.
**Frozen at end of Day 2** (task `D2-C4`). After that, a change here needs the team's
agreement and a note in this file's changelog.

Base URL `http://localhost:8080/api/v1` · Content type `application/json` ·
Errors `application/problem+json`

---

## 0. Conventions that apply to every endpoint

### 0.1 Authentication

Every endpoint except `/actuator/health`, `/v3/api-docs/**` and `/swagger-ui/**` requires:

```
Authorization: Bearer <Google ID token>
```

The token is validated against `https://accounts.google.com` — signature via JWKS, `iss`,
`aud` (must equal `GOOGLE_CLIENT_ID`) and `exp`. On first sight of a `sub` an `app_user` row
is created. `email_verified: false` is rejected with 403.

### 0.2 Money is always an object, and the amount is always a string

```json
{ "amount": "18654.7500", "currency": "INR" }
```

`amount` is a **JSON string**, not a number. IEEE-754 doubles cannot represent
`0.1 + 0.2` and JavaScript parses bare JSON numbers as doubles; sending a string means the
value that left `BigDecimal` is the value the browser renders. Scale is always 4 for money,
6 for quantity, 8 for FX rates. `currency` is ISO-4217 uppercase.

Quantities follow the same rule: `"quantity": "10.000000"`.

### 0.3 Currency semantics

| Field pattern | Meaning |
|---|---|
| `*.currency` inside a `price`, `avgCost` or `costBasisNative` | the instrument's **native** trading currency |
| every other money object in a portfolio response | the portfolio's **base** currency, or the `?currency=` override |
| `baseCurrency` on the portfolio | what portfolio-level totals are expressed in |

> **LSE gotcha, handled at ingestion:** London-listed instruments quote in GBX (pence).
> The provider adapter normalises GBX → GBP by dividing by 100 before anything is stored,
> so `instrument.currency` is never `GBX`. `PriceNormalisationTest` covers it.

### 0.4 Dates and times

- Instants: ISO-8601 UTC with `Z` — `2026-07-28T10:15:00Z`. Never a local offset.
- Dates: `2026-07-28`.
- `priceAsOf` / `rateAsOf` are dates, and they are how the client knows data is stale.

### 0.5 Staleness flags

Any response containing a market value carries:

```json
"dataQuality": { "priceAsOf": "2026-07-29", "rateAsOf": "2026-07-29", "stale": false }
```

`stale` is `true` when either date is more than 3 calendar days behind `asOf`. The UI shows
an amber badge; it does not fail. This is the visible face of the offline-safety rule.

### 0.6 Pagination

Query: `?page=0&size=20&sort=executedAt,desc`. `size` max 100, default 20.

```json
{ "content": [], "page": 0, "size": 20, "totalElements": 137, "totalPages": 7, "first": true, "last": false }
```

### 0.7 Errors — RFC 9457, one shape everywhere

```json
{
  "type": "https://portfolio.local/errors/insufficient-quantity",
  "title": "Insufficient quantity",
  "status": 422,
  "detail": "Cannot sell 50 of AAPL; holding is 20.",
  "instance": "/api/v1/portfolios/7/transactions",
  "correlationId": "1f9c2e40-7c3d-4a91-9a2e-8c1b5f0d3e77",
  "timestamp": "2026-07-30T09:14:22Z",
  "errors": [ { "field": "quantity", "message": "must not exceed holding" } ]
}
```

`errors[]` is present only for field-level validation failures. `correlationId` matches the
`X-Correlation-Id` response header and the server logs.

| Status | When | `type` slug examples |
|---|---|---|
| 200 | successful read | — |
| 201 | created, with `Location` | — |
| 204 | deleted | — |
| 400 | malformed request, bad query param, failed Bean Validation | `/errors/validation-failed`, `/errors/invalid-date-range` |
| 401 | missing, expired, malformed or unverifiable token | `/errors/unauthenticated` |
| 403 | authenticated but not permitted — only `email_verified: false` and admin endpoints | `/errors/forbidden` |
| 404 | not found **or owned by another user** | `/errors/portfolio-not-found`, `/errors/instrument-not-found` |
| 409 | uniqueness conflict | `/errors/duplicate-portfolio-name` |
| 422 | request is well-formed but breaks a domain rule | `/errors/insufficient-quantity`, `/errors/currency-mismatch` |
| 502 | upstream provider failed **and** no cached or seeded fallback existed | `/errors/upstream-unavailable` |
| 500 | anything else. Never leaks a stack trace, SQL or a class name | `/errors/internal` |

**Another user's resource returns 404, not 403.** A 403 confirms the row exists. That
distinction is tested in `CrossUserAccessIT`.

502 should be almost unreachable by design — §7 of ARCHITECTURE.md means a fallback nearly
always exists. It is reserved for a cold cache on an instrument with no seed data.

---

## 1. `GET /me`

Current user; created on first sign-in.

**Response 200** — `UserResponse`

| Field | Type | Notes |
|---|---|---|
| `id` | integer | internal user id |
| `email` | string | from the verified `email` claim |
| `displayName` | string \| null | `name` claim |
| `pictureUrl` | string \| null | `picture` claim |
| `createdAt` | instant | when the row was provisioned |

```bash
curl -s http://localhost:8080/api/v1/me -H "Authorization: Bearer $TOKEN"
```
```json
{
  "id": 42,
  "email": "dhruv@example.com",
  "displayName": "Dhruv Tiwari",
  "pictureUrl": "https://lh3.googleusercontent.com/a/ACg8oc...",
  "createdAt": "2026-07-31T08:02:11Z"
}
```

Codes: `200`, `401`, `403` (email not verified).

---

## 2. `GET /portfolios`

**Response 200** — `PortfolioResponse[]` (not paged; a user has few portfolios)

| Field | Type | Notes |
|---|---|---|
| `id` | integer | |
| `name` | string | |
| `baseCurrency` | enum | `USD` `EUR` `GBP` `INR` |
| `holdingCount` | integer | positions with quantity > 0 |
| `marketValue` | Money | in `baseCurrency` |
| `unrealisedPnl` | Money | |
| `unrealisedPnlPct` | string \| null | **`null` when cost basis is zero** — never `Infinity` |
| `dataQuality` | object | §0.5 |
| `createdAt` / `updatedAt` | instant | |

```bash
curl -s http://localhost:8080/api/v1/portfolios -H "Authorization: Bearer $TOKEN"
```
```json
[
  {
    "id": 7,
    "name": "Growth",
    "baseCurrency": "INR",
    "holdingCount": 3,
    "marketValue":   { "amount": "412873.5400", "currency": "INR" },
    "unrealisedPnl": { "amount": "38210.9100",  "currency": "INR" },
    "unrealisedPnlPct": "10.2100",
    "dataQuality": { "priceAsOf": "2026-07-29", "rateAsOf": "2026-07-29", "stale": false },
    "createdAt": "2026-02-14T11:03:00Z",
    "updatedAt": "2026-07-28T10:15:00Z"
  }
]
```

Codes: `200`, `401`.

---

## 3. `POST /portfolios`

**Request** — `CreatePortfolioRequest`

| Field | Type | Constraints |
|---|---|---|
| `name` | string | `@NotBlank`, `@Size(1,120)`, unique per user |
| `baseCurrency` | enum | `@NotNull`, one of `USD` `EUR` `GBP` `INR` |

```bash
curl -s -X POST http://localhost:8080/api/v1/portfolios \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"Growth","baseCurrency":"INR"}' -i
```
```
HTTP/1.1 201 Created
Location: /api/v1/portfolios/7
```
```json
{
  "id": 7, "name": "Growth", "baseCurrency": "INR", "holdingCount": 0,
  "marketValue":   { "amount": "0.0000", "currency": "INR" },
  "unrealisedPnl": { "amount": "0.0000", "currency": "INR" },
  "unrealisedPnlPct": null,
  "dataQuality": { "priceAsOf": null, "rateAsOf": null, "stale": false },
  "createdAt": "2026-07-31T09:00:00Z", "updatedAt": "2026-07-31T09:00:00Z"
}
```

Note `unrealisedPnlPct: null` on an empty portfolio — that is the zero-cost-basis rule, not
a bug.

Codes: `201`, `400`, `401`, `409` duplicate name.

---

## 4. `GET /portfolios/{id}`

Detail with summary valuation. Same body as §2 plus `cashBalance`, `costBasis`,
`realisedPnl`.

```bash
curl -s http://localhost:8080/api/v1/portfolios/7 -H "Authorization: Bearer $TOKEN"
```
```json
{
  "id": 7, "name": "Growth", "baseCurrency": "INR", "holdingCount": 3,
  "marketValue":   { "amount": "412873.5400", "currency": "INR" },
  "costBasis":     { "amount": "374662.6300", "currency": "INR" },
  "cashBalance":   { "amount": "25000.0000",  "currency": "INR" },
  "unrealisedPnl": { "amount": "38210.9100",  "currency": "INR" },
  "realisedPnl":   { "amount": "4120.0000",   "currency": "INR" },
  "unrealisedPnlPct": "10.2100",
  "dataQuality": { "priceAsOf": "2026-07-29", "rateAsOf": "2026-07-29", "stale": false },
  "createdAt": "2026-02-14T11:03:00Z", "updatedAt": "2026-07-28T10:15:00Z"
}
```

Codes: `200`, `401`, `404`.

---

## 5. `PATCH /portfolios/{id}`

**Added to §4 of the reference design** — needed because base currency is user-changeable.

**Request** — `UpdatePortfolioRequest`, all fields optional, at least one required

| Field | Type | Constraints |
|---|---|---|
| `name` | string \| null | `@Size(1,120)`, unique per user |
| `baseCurrency` | enum \| null | one of `USD` `EUR` `GBP` `INR` |

Changing `baseCurrency` **rewrites no `txn` and no `holding` row** — it changes only how
totals are presented. `BaseCurrencyImmutabilityIT` asserts a byte-identical `txn` and
`holding` table before and after.

```bash
curl -s -X PATCH http://localhost:8080/api/v1/portfolios/7 \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"baseCurrency":"USD"}'
```
```json
{
  "id": 7, "name": "Growth", "baseCurrency": "USD", "holdingCount": 3,
  "marketValue":   { "amount": "4745.6900", "currency": "USD" },
  "costBasis":     { "amount": "4306.4700", "currency": "USD" },
  "cashBalance":   { "amount": "287.3600",  "currency": "USD" },
  "unrealisedPnl": { "amount": "439.2200",  "currency": "USD" },
  "realisedPnl":   { "amount": "47.3600",   "currency": "USD" },
  "unrealisedPnlPct": "10.2100",
  "dataQuality": { "priceAsOf": "2026-07-29", "rateAsOf": "2026-07-29", "stale": false },
  "createdAt": "2026-02-14T11:03:00Z", "updatedAt": "2026-07-31T09:22:41Z"
}
```

Same holdings, same transactions, every number re-expressed. `unrealisedPnlPct` is
essentially unchanged because it is a ratio — small differences come from FX moving between
the trade dates and today, which is exactly what it should do.

Codes: `200`, `400`, `401`, `404`, `409`.

---

## 6. `DELETE /portfolios/{id}`

Deletes the portfolio and cascades to its transactions, holdings and valuation snapshots in
one DB transaction.

```bash
curl -s -X DELETE http://localhost:8080/api/v1/portfolios/7 \
  -H "Authorization: Bearer $TOKEN" -i
```
```
HTTP/1.1 204 No Content
```

Codes: `204`, `401`, `404`.

---

## 7. `GET /portfolios/{id}/transactions`

Paged, filterable.

| Query param | Type | Default | Constraints |
|---|---|---|---|
| `type` | enum | all | `BUY` `SELL` `DIVIDEND` `DEPOSIT` `WITHDRAWAL` `FEE` |
| `symbol` | string | all | exact match |
| `from` / `to` | date | all | `from ≤ to`, else 400 |
| `page` / `size` / `sort` | | `0` / `20` / `executedAt,desc` | `size ≤ 100` |

**`TransactionResponse`**

| Field | Type | Notes |
|---|---|---|
| `id` | integer | |
| `type` | enum | |
| `instrument` | object \| null | `null` for `DEPOSIT` / `WITHDRAWAL` |
| `instrument.symbol` / `.name` / `.currency` / `.assetType` | | |
| `quantity` | string | scale 6 |
| `price` | Money | **native** currency |
| `fees` | Money | native currency |
| `totalNative` | Money | `quantity × price + fees` for BUY, `− fees` for SELL |
| `totalBase` | Money | converted at the FX rate **on `executedAt`** |
| `fxRateApplied` | string \| null | scale 8; `null` when native == base |
| `executedAt` | instant | |
| `note` | string \| null | ≤ 500 chars |

```bash
curl -s "http://localhost:8080/api/v1/portfolios/7/transactions?type=BUY&from=2026-01-01&size=2" \
  -H "Authorization: Bearer $TOKEN"
```
```json
{
  "content": [
    {
      "id": 91,
      "type": "BUY",
      "instrument": { "symbol": "RELIANCE", "name": "Reliance Industries Ltd", "currency": "INR", "assetType": "STOCK" },
      "quantity": "10.000000",
      "price":       { "amount": "1450.2500", "currency": "INR" },
      "fees":        { "amount": "24.5000",   "currency": "INR" },
      "totalNative": { "amount": "14527.0000", "currency": "INR" },
      "totalBase":   { "amount": "14527.0000", "currency": "INR" },
      "fxRateApplied": null,
      "executedAt": "2026-07-28T10:15:00Z",
      "note": "Post-results add"
    },
    {
      "id": 88,
      "type": "BUY",
      "instrument": { "symbol": "AAPL", "name": "Apple Inc.", "currency": "USD", "assetType": "STOCK" },
      "quantity": "12.000000",
      "price":       { "amount": "214.5000", "currency": "USD" },
      "fees":        { "amount": "1.9900",   "currency": "USD" },
      "totalNative": { "amount": "2575.9900",   "currency": "USD" },
      "totalBase":   { "amount": "223867.1300", "currency": "INR" },
      "fxRateApplied": "86.90000000",
      "executedAt": "2026-06-11T14:30:00Z",
      "note": null
    }
  ],
  "page": 0, "size": 2, "totalElements": 24, "totalPages": 12, "first": true, "last": false
}
```

`fxRateApplied: "86.90000000"` is the USD→INR rate on 11 Jun 2026, not today's. That is what
makes cost basis economically correct.

Codes: `200`, `400` (`from > to`), `401`, `404`.

---

## 8. `POST /portfolios/{id}/transactions` — **the customer's "add"**

**Request** — `CreateTransactionRequest`

| Field | Type | Constraints |
|---|---|---|
| `type` | enum | `@NotNull`, one of the six |
| `symbol` | string \| null | `@NotBlank` for `BUY` `SELL` `DIVIDEND` `FEE`; must be `null` for `DEPOSIT` `WITHDRAWAL` |
| `quantity` | string | `@NotNull`, `@DecimalMin("0.000001")`, ≤ 6 dp. Must be `0` for `DEPOSIT` `WITHDRAWAL` `FEE` |
| `price` | string | `@NotNull`, `@DecimalMin("0.0000")`, ≤ 4 dp. For `DEPOSIT`/`WITHDRAWAL` this is the cash amount |
| `currency` | enum | `@NotNull`. Must equal the instrument's currency, else `422 /errors/currency-mismatch` |
| `fees` | string | optional, default `"0"`, `@DecimalMin("0.0000")` |
| `executedAt` | instant | `@NotNull`, `@PastOrPresent`. A future date is `400` |
| `note` | string \| null | `@Size(max=500)` |

**Response 201** — `TransactionResponse` plus:

| Field | Type | Notes |
|---|---|---|
| `warnings` | string[] | empty array when there is nothing to say |

Warnings are advisory and never block the write. The only one in v1 is negative cash after a
BUY — see `/docs/PLAN.md` §2.7 for why this warns rather than rejects.

```bash
curl -s -X POST http://localhost:8080/api/v1/portfolios/7/transactions \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{
    "type": "BUY", "symbol": "RELIANCE", "quantity": "10", "price": "1450.25",
    "currency": "INR", "fees": "24.50", "executedAt": "2026-07-28T10:15:00Z",
    "note": "Post-results add"
  }' -i
```
```
HTTP/1.1 201 Created
Location: /api/v1/portfolios/7/transactions/91
```
```json
{
  "id": 91, "type": "BUY",
  "instrument": { "symbol": "RELIANCE", "name": "Reliance Industries Ltd", "currency": "INR", "assetType": "STOCK" },
  "quantity": "10.000000",
  "price":       { "amount": "1450.2500", "currency": "INR" },
  "fees":        { "amount": "24.5000",   "currency": "INR" },
  "totalNative": { "amount": "14527.0000", "currency": "INR" },
  "totalBase":   { "amount": "14527.0000", "currency": "INR" },
  "fxRateApplied": null,
  "executedAt": "2026-07-28T10:15:00Z",
  "note": "Post-results add",
  "warnings": ["Purchase exceeds available cash by ₹4,527.00; cash balance is now negative."]
}
```

**Selling more than you hold — 422:**
```bash
curl -s -X POST http://localhost:8080/api/v1/portfolios/7/transactions \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"type":"SELL","symbol":"AAPL","quantity":"50","price":"228.10","currency":"USD","executedAt":"2026-07-30T09:00:00Z"}'
```
```json
{
  "type": "https://portfolio.local/errors/insufficient-quantity",
  "title": "Insufficient quantity",
  "status": 422,
  "detail": "Cannot sell 50 of AAPL; holding is 12.",
  "instance": "/api/v1/portfolios/7/transactions",
  "correlationId": "1f9c2e40-7c3d-4a91-9a2e-8c1b5f0d3e77",
  "timestamp": "2026-07-30T09:14:22Z",
  "errors": [ { "field": "quantity", "message": "must not exceed holding of 12.000000" } ]
}
```

**Unknown symbol — 404, and nothing is written:**
```json
{
  "type": "https://portfolio.local/errors/instrument-not-found",
  "title": "Instrument not found", "status": 404,
  "detail": "No instrument with symbol 'TSLAA'.",
  "instance": "/api/v1/portfolios/7/transactions",
  "correlationId": "9b2f...", "timestamp": "2026-07-30T09:15:02Z"
}
```

**Currency mismatch — 422:**
```json
{
  "type": "https://portfolio.local/errors/currency-mismatch",
  "title": "Currency mismatch", "status": 422,
  "detail": "AAPL trades in USD; transaction supplied INR.",
  "instance": "/api/v1/portfolios/7/transactions",
  "correlationId": "3c81...", "timestamp": "2026-07-30T09:16:40Z",
  "errors": [ { "field": "currency", "message": "must be USD for AAPL" } ]
}
```

Codes: `201`, `400`, `401`, `404`, `422`.

---

## 9. `DELETE /portfolios/{id}/transactions/{txnId}` — **the customer's "remove"**

Deletes the transaction and **rebuilds the entire holdings projection** for that portfolio
from the remaining `txn` rows, in the same DB transaction, under a row lock. Deleting the
oldest of twenty transactions is as correct as deleting the newest.

```bash
curl -s -X DELETE http://localhost:8080/api/v1/portfolios/7/transactions/91 \
  -H "Authorization: Bearer $TOKEN" -i
```
```
HTTP/1.1 204 No Content
```

Codes: `204`, `401`, `404` (unknown txn, txn in another portfolio, or another user's).

---

## 10. `GET /portfolios/{id}/holdings` — **the customer's "browse"**

| Query param | Type | Default | Notes |
|---|---|---|---|
| `currency` | enum | portfolio base | presentation override; changes nothing stored |
| `includeZero` | boolean | `false` | `true` shows closed positions (quantity 0) with their realised P&L |

**`HoldingResponse`**

| Field | Type | Notes |
|---|---|---|
| `instrument` | object | `symbol` `name` `assetType` `currency` `exchange` `sector` |
| `quantity` | string | scale 6 |
| `avgCost` | Money | **native** currency, straight from `holding.avg_cost` |
| `lastPrice` | Money | native currency |
| `marketValue` | Money | **base** currency |
| `costBasis` | Money | base currency, FX at trade dates |
| `unrealisedPnl` | Money | base currency |
| `unrealisedPnlPct` | string \| null | `null` when cost basis is zero |
| `realisedPnl` | Money | base currency |
| `weightPct` | string | share of portfolio market value, scale 4 |
| `fxRate` | string \| null | current native→base rate, scale 8 |
| `dataQuality` | object | per-holding staleness |

```bash
curl -s "http://localhost:8080/api/v1/portfolios/7/holdings" -H "Authorization: Bearer $TOKEN"
```
```json
[
  {
    "instrument": { "symbol": "AAPL", "name": "Apple Inc.", "assetType": "STOCK", "currency": "USD", "exchange": "NASDAQ", "sector": "Technology" },
    "quantity": "12.000000",
    "avgCost":       { "amount": "214.6658", "currency": "USD" },
    "lastPrice":     { "amount": "228.1000", "currency": "USD" },
    "marketValue":   { "amount": "238156.2000", "currency": "INR" },
    "costBasis":     { "amount": "223867.1300", "currency": "INR" },
    "unrealisedPnl": { "amount": "14289.0700",  "currency": "INR" },
    "unrealisedPnlPct": "6.3800",
    "realisedPnl":   { "amount": "0.0000", "currency": "INR" },
    "weightPct": "57.6800",
    "fxRate": "87.00000000",
    "dataQuality": { "priceAsOf": "2026-07-29", "rateAsOf": "2026-07-29", "stale": false }
  },
  {
    "instrument": { "symbol": "RELIANCE", "name": "Reliance Industries Ltd", "assetType": "STOCK", "currency": "INR", "exchange": "NSE", "sector": "Energy" },
    "quantity": "10.000000",
    "avgCost":       { "amount": "1452.7000", "currency": "INR" },
    "lastPrice":     { "amount": "1489.6000", "currency": "INR" },
    "marketValue":   { "amount": "14896.0000", "currency": "INR" },
    "costBasis":     { "amount": "14527.0000", "currency": "INR" },
    "unrealisedPnl": { "amount": "369.0000",   "currency": "INR" },
    "unrealisedPnlPct": "2.5400",
    "realisedPnl":   { "amount": "0.0000", "currency": "INR" },
    "weightPct": "3.6100",
    "fxRate": null,
    "dataQuality": { "priceAsOf": "2026-07-29", "rateAsOf": "2026-07-29", "stale": false }
  },
  {
    "instrument": { "symbol": "SHEL", "name": "Shell plc", "assetType": "STOCK", "currency": "GBP", "exchange": "LSE", "sector": "Energy" },
    "quantity": "40.000000",
    "avgCost":       { "amount": "27.4400", "currency": "GBP" },
    "lastPrice":     { "amount": "29.1500", "currency": "GBP" },
    "marketValue":   { "amount": "134090.0000", "currency": "INR" },
    "costBasis":     { "amount": "123480.0000", "currency": "INR" },
    "unrealisedPnl": { "amount": "10610.0000",  "currency": "INR" },
    "unrealisedPnlPct": "8.5900",
    "realisedPnl":   { "amount": "0.0000", "currency": "INR" },
    "weightPct": "32.4800",
    "fxRate": "115.00000000",
    "dataQuality": { "priceAsOf": "2026-07-29", "rateAsOf": "2026-07-29", "stale": false }
  }
]
```

`avgCost` stays in USD, GBP and INR respectively; every portfolio-level figure is INR. That
is the whole currency model visible in one response.

Codes: `200`, `400` (unknown `currency`), `401`, `404`.

---

## 11. `GET /portfolios/{id}/valuation?asOf=`

| Query param | Type | Default | Notes |
|---|---|---|---|
| `asOf` | date | today | historical valuation; must be ≥ the first transaction date |
| `currency` | enum | portfolio base | presentation override |

```bash
curl -s "http://localhost:8080/api/v1/portfolios/7/valuation?asOf=2026-06-30" \
  -H "Authorization: Bearer $TOKEN"
```
```json
{
  "portfolioId": 7,
  "asOf": "2026-06-30",
  "currency": "INR",
  "marketValue":   { "amount": "389442.1000", "currency": "INR" },
  "costBasis":     { "amount": "374662.6300", "currency": "INR" },
  "cashBalance":   { "amount": "25000.0000",  "currency": "INR" },
  "totalValue":    { "amount": "414442.1000", "currency": "INR" },
  "unrealisedPnl": { "amount": "14779.4700",  "currency": "INR" },
  "realisedPnl":   { "amount": "4120.0000",   "currency": "INR" },
  "unrealisedPnlPct": "3.9400",
  "holdingCount": 3,
  "dataQuality": { "priceAsOf": "2026-06-30", "rateAsOf": "2026-06-30", "stale": false }
}
```

`totalValue = marketValue + cashBalance`. Codes: `200`, `400`, `401`, `404`.

---

## 12. `GET /portfolios/{id}/performance?from=&to=` — **the customer's "view performance"**

| Query param | Type | Default | Constraints |
|---|---|---|---|
| `from` | date | 1 year ago | `from ≤ to`, range ≤ 5 years else `400` |
| `to` | date | today | |
| `interval` | enum | `DAILY` | `DAILY` `WEEKLY` `MONTHLY` |
| `currency` | enum | portfolio base | |

One point per interval, **forward-filled** across weekends, market holidays and any date
with no price. Points before the first transaction are omitted, not zero-filled.

```bash
curl -s "http://localhost:8080/api/v1/portfolios/7/performance?from=2026-07-24&to=2026-07-29" \
  -H "Authorization: Bearer $TOKEN"
```
```json
{
  "portfolioId": 7,
  "currency": "INR",
  "from": "2026-07-24",
  "to": "2026-07-29",
  "interval": "DAILY",
  "points": [
    { "date": "2026-07-24", "marketValue": {"amount":"401220.4000","currency":"INR"}, "costBasis": {"amount":"374662.6300","currency":"INR"}, "cashBalance": {"amount":"39527.0000","currency":"INR"}, "totalValue": {"amount":"440747.4000","currency":"INR"}, "unrealisedPnl": {"amount":"26557.7700","currency":"INR"}, "filled": false },
    { "date": "2026-07-25", "marketValue": {"amount":"403981.1200","currency":"INR"}, "costBasis": {"amount":"374662.6300","currency":"INR"}, "cashBalance": {"amount":"39527.0000","currency":"INR"}, "totalValue": {"amount":"443508.1200","currency":"INR"}, "unrealisedPnl": {"amount":"29318.4900","currency":"INR"}, "filled": false },
    { "date": "2026-07-26", "marketValue": {"amount":"403981.1200","currency":"INR"}, "costBasis": {"amount":"374662.6300","currency":"INR"}, "cashBalance": {"amount":"39527.0000","currency":"INR"}, "totalValue": {"amount":"443508.1200","currency":"INR"}, "unrealisedPnl": {"amount":"29318.4900","currency":"INR"}, "filled": true },
    { "date": "2026-07-27", "marketValue": {"amount":"403981.1200","currency":"INR"}, "costBasis": {"amount":"374662.6300","currency":"INR"}, "cashBalance": {"amount":"39527.0000","currency":"INR"}, "totalValue": {"amount":"443508.1200","currency":"INR"}, "unrealisedPnl": {"amount":"29318.4900","currency":"INR"}, "filled": true },
    { "date": "2026-07-28", "marketValue": {"amount":"411440.0100","currency":"INR"}, "costBasis": {"amount":"389189.6300","currency":"INR"}, "cashBalance": {"amount":"25000.0000","currency":"INR"}, "totalValue": {"amount":"436440.0100","currency":"INR"}, "unrealisedPnl": {"amount":"22250.3800","currency":"INR"}, "filled": false },
    { "date": "2026-07-29", "marketValue": {"amount":"412873.5400","currency":"INR"}, "costBasis": {"amount":"389189.6300","currency":"INR"}, "cashBalance": {"amount":"25000.0000","currency":"INR"}, "totalValue": {"amount":"436440.0100","currency":"INR"}, "unrealisedPnl": {"amount":"23683.9100","currency":"INR"}, "filled": false }
  ],
  "summary": {
    "startValue": {"amount":"440747.4000","currency":"INR"},
    "endValue":   {"amount":"436440.0100","currency":"INR"},
    "absoluteChange": {"amount":"-4307.3900","currency":"INR"},
    "percentChange": "-0.9800",
    "netContributions": {"amount":"0.0000","currency":"INR"}
  },
  "dataQuality": { "priceAsOf": "2026-07-29", "rateAsOf": "2026-07-29", "stale": false }
}
```

`26 Jul` and `27 Jul` are a weekend — `"filled": true` and the value is carried forward.
`28 Jul` shows `costBasis` jumping because a BUY settled that day; `netContributions` in the
summary is how the client separates "the market moved" from "I added money".

Codes: `200`, `400` (`from > to`, range too wide), `401`, `404`.

---

## 13. `GET /portfolios/{id}/allocation`

| Query param | Type | Default |
|---|---|---|
| `by` | enum | `ASSET_TYPE`. Also `SECTOR`, `CURRENCY`, `INSTRUMENT` |
| `currency` | enum | portfolio base |

```bash
curl -s "http://localhost:8080/api/v1/portfolios/7/allocation?by=CURRENCY" \
  -H "Authorization: Bearer $TOKEN"
```
```json
{
  "portfolioId": 7, "by": "CURRENCY", "currency": "INR",
  "total": { "amount": "412873.5400", "currency": "INR" },
  "slices": [
    { "key": "USD", "label": "US Dollar",     "value": {"amount":"238156.2000","currency":"INR"}, "weightPct": "57.6800", "instrumentCount": 1 },
    { "key": "GBP", "label": "Pound Sterling","value": {"amount":"134090.0000","currency":"INR"}, "weightPct": "32.4800", "instrumentCount": 1 },
    { "key": "INR", "label": "Indian Rupee",  "value": {"amount":"14896.0000","currency":"INR"},  "weightPct": "3.6100",  "instrumentCount": 1 }
  ],
  "dataQuality": { "priceAsOf": "2026-07-29", "rateAsOf": "2026-07-29", "stale": false }
}
```

`weightPct` values sum to 100 ± 0.01; residual rounding lands on the largest slice.
Cash is excluded from allocation and reported separately.

Codes: `200`, `400`, `401`, `404`.

---

## 14. `GET /instruments?query=`

Type-ahead for the add form. Not user-scoped — the instrument catalogue is shared.

| Query param | Type | Default | Constraints |
|---|---|---|---|
| `query` | string | — | `@NotBlank`, `@Size(1,50)`. Symbol prefix ranks above name substring |
| `assetType` | enum | all | |
| `currency` | enum | all | |
| `limit` | integer | 20 | `@Max(50)` |

```bash
curl -s "http://localhost:8080/api/v1/instruments?query=rel&limit=2" -H "Authorization: Bearer $TOKEN"
```
```json
[
  { "id": 12, "symbol": "RELIANCE", "name": "Reliance Industries Ltd", "assetType": "STOCK", "currency": "INR", "exchange": "NSE", "sector": "Energy" },
  { "id": 31, "symbol": "RELIGARE", "name": "Religare Enterprises Ltd", "assetType": "STOCK", "currency": "INR", "exchange": "NSE", "sector": "Financials" }
]
```

Codes: `200`, `400`, `401`.

---

## 15. `GET /instruments/{symbol}/prices?from=&to=`

```bash
curl -s "http://localhost:8080/api/v1/instruments/AAPL/prices?from=2026-07-27&to=2026-07-29" \
  -H "Authorization: Bearer $TOKEN"
```
```json
{
  "symbol": "AAPL", "currency": "USD", "from": "2026-07-27", "to": "2026-07-29",
  "prices": [
    { "date": "2026-07-27", "close": {"amount":"226.4000","currency":"USD"}, "source": "YAHOO" },
    { "date": "2026-07-28", "close": {"amount":"227.1500","currency":"USD"}, "source": "YAHOO" },
    { "date": "2026-07-29", "close": {"amount":"228.1000","currency":"USD"}, "source": "YAHOO" }
  ]
}
```

`source` is one of `YAHOO` `TWELVE_DATA` `YFINANCE` `MANUAL` `SEED` — a `SEED` value on the
demo machine is the honest signal that the row came from the offline fallback.

Codes: `200`, `400`, `401`, `404` (unknown symbol).

---

## 16. `GET /fx/rates`

| Query param | Type | Default |
|---|---|---|
| `base` | enum | `USD` |
| `symbols` | csv | all supported |
| `on` | date | latest |

```bash
curl -s "http://localhost:8080/api/v1/fx/rates?base=USD&symbols=INR,GBP,EUR" \
  -H "Authorization: Bearer $TOKEN"
```
```json
{
  "base": "USD", "asOf": "2026-07-29", "source": "FRANKFURTER", "stale": false,
  "rates": { "INR": "87.00000000", "GBP": "0.75652000", "EUR": "0.91340000" }
}
```

Codes: `200`, `400`, `401`.

---

## 17. `POST /admin/prices/refresh`

Triggers an immediate refresh. Restricted to the email addresses in `ADMIN_EMAILS`.

**Request** — `RefreshPricesRequest`

| Field | Type | Constraints |
|---|---|---|
| `symbols` | string[] \| null | `null` = all seeded instruments; `@Size(max=50)` |
| `from` | date \| null | backfill start; default = last stored date + 1 |

```bash
curl -s -X POST http://localhost:8080/api/v1/admin/prices/refresh \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"symbols":["AAPL","RELIANCE"]}'
```
```json
{
  "requested": 2, "updated": 2, "skipped": 0, "failed": 0,
  "results": [
    { "symbol": "AAPL",     "status": "UPDATED", "rowsWritten": 1, "latestDate": "2026-07-29", "source": "YAHOO" },
    { "symbol": "RELIANCE", "status": "UPDATED", "rowsWritten": 1, "latestDate": "2026-07-29", "source": "YAHOO" }
  ]
}
```

A provider failure yields `"status": "FAILED"` on that row with the request still `200` —
partial success is the normal outcome under rate limiting, not an error.

Codes: `200`, `400`, `401`, `403` (not an admin), `502` (every symbol failed **and** no
cached data existed).

---

## 18. Stretch endpoints

Behind `features.insights.enabled`. When disabled they return `501 Not Implemented` with a
clean `ProblemDetail` — never a 404, so the frontend can tell "turned off" from "wrong URL".

### `POST /portfolios/{id}/insights`

```bash
curl -s -X POST http://localhost:8080/api/v1/portfolios/7/insights \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"horizon":"1M","tone":"concise"}'
```
```json
{
  "portfolioId": 7,
  "generatedAt": "2026-07-30T09:40:12Z",
  "engine": "LLM",
  "summary": "Your portfolio is up 10.2% against cost, driven mainly by AAPL (+6.4%) and Shell (+8.6%). Currency is doing real work here: 90% of the book is priced outside your INR base, so a 1% move in USD/INR shifts your total by roughly ₹2,400 before any stock moves.",
  "highlights": [
    { "type": "CONCENTRATION", "severity": "MEDIUM", "message": "AAPL is 57.7% of market value." },
    { "type": "FX_EXPOSURE",   "severity": "MEDIUM", "message": "90.2% of holdings are non-INR." }
  ],
  "disclaimer": "Generated commentary. Not investment advice."
}
```

`engine` is `LLM` when the FastAPI service answered and **`RULE_BASED`** when it did not —
the fallback is visible, not hidden. Codes: `200`, `400`, `401`, `404`, `501`.

### `POST /portfolios/{id}/query`

Natural language → a structured filter, which the client then replays against §7.

```bash
curl -s -X POST http://localhost:8080/api/v1/portfolios/7/query \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"question":"what did I buy in June"}'
```
```json
{
  "interpretation": "BUY transactions between 2026-06-01 and 2026-06-30",
  "filter": { "type": "BUY", "from": "2026-06-01", "to": "2026-06-30" },
  "confidence": "HIGH",
  "resultUrl": "/api/v1/portfolios/7/transactions?type=BUY&from=2026-06-01&to=2026-06-30"
}
```

The LLM never generates SQL and never touches the database. It emits a filter object that is
validated against the same Bean Validation constraints as §7 before use. Codes: `200`,
`400`, `401`, `404`, `501`.

### `GET /portfolios/{id}/optimize`

**Cut.** See `/docs/DECISIONS/0009-quantum-cut.md`. Not implemented, not routed.

---

## 19. GraphQL

Single endpoint `POST /graphql`, GraphiQL at `/graphiql` in the local profile only.
Same Bearer token, same security context, same `userId` scoping — **the resolvers call the
same services as REST**, so an authorisation bug cannot exist in one and not the other.

Read-only in v1. Mutations stay on REST because that is where the transactional and
`Location`-header semantics live. Query depth is limited to 6 and complexity to 200.

```graphql
scalar Date          # 2026-07-29
scalar DateTime      # 2026-07-29T10:15:00Z
scalar Decimal       # always serialised as a string: "1450.2500"

enum Currency    { USD EUR GBP INR }
enum AssetType   { STOCK ETF MUTUAL_FUND BOND TREASURY CASH CRYPTO }
enum TxnType     { BUY SELL DIVIDEND DEPOSIT WITHDRAWAL FEE }
enum Interval    { DAILY WEEKLY MONTHLY }
enum AllocateBy  { ASSET_TYPE SECTOR CURRENCY INSTRUMENT }
enum PriceSource { YAHOO TWELVE_DATA YFINANCE MANUAL SEED }

type Money {
  amount:   Decimal!
  currency: Currency!
}

type DataQuality {
  priceAsOf: Date
  rateAsOf:  Date
  stale:     Boolean!
}

type User {
  id:          ID!
  email:       String!
  displayName: String
  pictureUrl:  String
  createdAt:   DateTime!
  portfolios:  [Portfolio!]!
}

type Instrument {
  id:        ID!
  symbol:    String!
  name:      String!
  assetType: AssetType!
  currency:  Currency!
  exchange:  String
  sector:    String
  prices(from: Date!, to: Date!): [PricePoint!]!
}

type PricePoint {
  date:   Date!
  close:  Money!
  source: PriceSource!
}

type Portfolio {
  id:               ID!
  name:             String!
  baseCurrency:     Currency!
  holdingCount:     Int!
  marketValue:      Money!
  costBasis:        Money!
  cashBalance:      Money!
  totalValue:       Money!
  unrealisedPnl:    Money!
  realisedPnl:      Money!
  unrealisedPnlPct: Decimal            # null when cost basis is zero
  dataQuality:      DataQuality!
  createdAt:        DateTime!
  updatedAt:        DateTime!

  holdings(includeZero: Boolean = false, currency: Currency): [Holding!]!
  transactions(type: TxnType, symbol: String, from: Date, to: Date,
               page: Int = 0, size: Int = 20): TransactionPage!
  valuation(asOf: Date, currency: Currency): Valuation!
  performance(from: Date, to: Date, interval: Interval = DAILY,
              currency: Currency): Performance!
  allocation(by: AllocateBy = ASSET_TYPE, currency: Currency): Allocation!
}

type Holding {
  instrument:       Instrument!
  quantity:         Decimal!
  avgCost:          Money!     # native currency
  lastPrice:        Money!     # native currency
  marketValue:      Money!     # base currency
  costBasis:        Money!
  unrealisedPnl:    Money!
  unrealisedPnlPct: Decimal
  realisedPnl:      Money!
  weightPct:        Decimal!
  fxRate:           Decimal
  dataQuality:      DataQuality!
}

type Transaction {
  id:            ID!
  type:          TxnType!
  instrument:    Instrument          # null for DEPOSIT / WITHDRAWAL
  quantity:      Decimal!
  price:         Money!
  fees:          Money!
  totalNative:   Money!
  totalBase:     Money!
  fxRateApplied: Decimal
  executedAt:    DateTime!
  note:          String
}

type TransactionPage {
  content:       [Transaction!]!
  page:          Int!
  size:          Int!
  totalElements: Int!
  totalPages:    Int!
}

type Valuation {
  asOf:             Date!
  currency:         Currency!
  marketValue:      Money!
  costBasis:        Money!
  cashBalance:      Money!
  totalValue:       Money!
  unrealisedPnl:    Money!
  realisedPnl:      Money!
  unrealisedPnlPct: Decimal
  holdingCount:     Int!
  dataQuality:      DataQuality!
}

type PerformancePoint {
  date:          Date!
  marketValue:   Money!
  costBasis:     Money!
  cashBalance:   Money!
  totalValue:    Money!
  unrealisedPnl: Money!
  filled:        Boolean!     # true when carried forward over a non-trading day
}

type PerformanceSummary {
  startValue:       Money!
  endValue:         Money!
  absoluteChange:   Money!
  percentChange:    Decimal
  netContributions: Money!
}

type Performance {
  currency:    Currency!
  from:        Date!
  to:          Date!
  interval:    Interval!
  points:      [PerformancePoint!]!
  summary:     PerformanceSummary!
  dataQuality: DataQuality!
}

type AllocationSlice {
  key:             String!
  label:           String!
  value:           Money!
  weightPct:       Decimal!
  instrumentCount: Int!
}

type Allocation {
  by:          AllocateBy!
  currency:    Currency!
  total:       Money!
  slices:      [AllocationSlice!]!
  dataQuality: DataQuality!
}

type Query {
  me: User!
  portfolios: [Portfolio!]!
  portfolio(id: ID!): Portfolio            # null if absent OR owned by another user
  instruments(query: String!, assetType: AssetType,
              currency: Currency, limit: Int = 20): [Instrument!]!
  instrument(symbol: String!): Instrument
  fxRates(base: Currency = USD, on: Date): [FxRate!]!
}

type FxRate {
  base:   Currency!
  quote:  Currency!
  rate:   Decimal!
  asOf:   Date!
  stale:  Boolean!
}
```

The query that justifies having GraphQL at all — one round trip for a screen that costs REST
four:

```bash
curl -s -X POST http://localhost:8080/graphql \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{
  "query": "query Dashboard($id: ID!, $from: Date!, $to: Date!) { portfolio(id: $id) { name baseCurrency totalValue { amount currency } unrealisedPnlPct holdings { instrument { symbol } quantity marketValue { amount } weightPct } performance(from: $from, to: $to) { points { date totalValue { amount } } summary { percentChange } } allocation(by: CURRENCY) { slices { key weightPct } } } }",
  "variables": { "id": "7", "from": "2026-07-24", "to": "2026-07-29" }
}'
```

GraphQL errors do **not** use ProblemDetail — the spec mandates an `errors[]` array. The
`extensions` object carries our `correlationId` and the same `classification` slug, so a
failure is traceable across both APIs.

```json
{
  "data": { "portfolio": null },
  "errors": [{
    "message": "Portfolio not found",
    "path": ["portfolio"],
    "extensions": { "classification": "NOT_FOUND", "correlationId": "1f9c2e40-..." }
  }]
}
```

---

## 20. Changelog

| Date | Change |
|---|---|
| Day 0 | Drafted from REFERENCE_DESIGN §4 |
| Day 0 | **Added** `PATCH /portfolios/{id}`, `GET /fx/rates`; **added** `?currency=` override, `dataQuality`, `warnings[]`, `totalBase`/`fxRateApplied`; **removed** `/optimize` |
| Day 2 | *(to be filled)* frozen against shipped code — `D2-C4` |
