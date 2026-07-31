# Reference Design — commit this to `/docs/` before anyone writes code

The shared contract all three developers code against. Change it together, on Day 0,
then treat it as frozen. Everything below is transaction-centric: transactions are the
source of truth, holdings are a projection recomputed from them.

---

## 1. MySQL schema — `V1__baseline.sql`

Notes before you paste it: `TRANSACTION` and `POSITION` are reserved words in MySQL, so
the tables are `txn` and `holding`. Money is `DECIMAL(19,4)`, quantities are
`DECIMAL(19,6)`. Timestamps are `DATETIME(6)` holding UTC — the app converts, never the
database.

```sql
CREATE TABLE app_user (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    google_sub   VARCHAR(255) NOT NULL,
    email        VARCHAR(320) NOT NULL,
    display_name VARCHAR(255),
    picture_url  VARCHAR(1024),
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                 ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_app_user_google_sub UNIQUE (google_sub),
    CONSTRAINT uk_app_user_email      UNIQUE (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE instrument (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol     VARCHAR(20)  NOT NULL,
    name       VARCHAR(255) NOT NULL,
    asset_type VARCHAR(16)  NOT NULL,
    currency   CHAR(3)      NOT NULL,
    exchange   VARCHAR(32),
    sector     VARCHAR(64),
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_instrument_symbol UNIQUE (symbol)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE portfolio (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id       BIGINT       NOT NULL,
    name          VARCHAR(120) NOT NULL,
    base_currency CHAR(3)      NOT NULL DEFAULT 'USD',
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                  ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_portfolio_user      FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT uk_portfolio_user_name UNIQUE (user_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE txn (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    portfolio_id  BIGINT         NOT NULL,
    instrument_id BIGINT         NULL,          -- NULL for DEPOSIT / WITHDRAWAL
    txn_type      VARCHAR(16)    NOT NULL,
    quantity      DECIMAL(19,6)  NOT NULL DEFAULT 0,
    price         DECIMAL(19,4)  NOT NULL DEFAULT 0,
    fees          DECIMAL(19,4)  NOT NULL DEFAULT 0,
    currency      CHAR(3)        NOT NULL,
    executed_at   DATETIME(6)    NOT NULL,
    note          VARCHAR(500),
    created_at    DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_txn_portfolio  FOREIGN KEY (portfolio_id)  REFERENCES portfolio(id),
    CONSTRAINT fk_txn_instrument FOREIGN KEY (instrument_id) REFERENCES instrument(id),
    CONSTRAINT ck_txn_quantity   CHECK (quantity >= 0),
    CONSTRAINT ck_txn_price      CHECK (price >= 0),
    INDEX idx_txn_portfolio_executed (portfolio_id, executed_at),
    INDEX idx_txn_instrument (instrument_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- projection, rebuildable from txn at any time
CREATE TABLE holding (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    portfolio_id  BIGINT        NOT NULL,
    instrument_id BIGINT        NOT NULL,
    quantity      DECIMAL(19,6) NOT NULL,
    avg_cost      DECIMAL(19,4) NOT NULL,
    realised_pnl  DECIMAL(19,4) NOT NULL DEFAULT 0,
    updated_at    DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                  ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_holding_portfolio  FOREIGN KEY (portfolio_id)  REFERENCES portfolio(id),
    CONSTRAINT fk_holding_instrument FOREIGN KEY (instrument_id) REFERENCES instrument(id),
    CONSTRAINT uk_holding UNIQUE (portfolio_id, instrument_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE price_history (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    instrument_id BIGINT        NOT NULL,
    price_date    DATE          NOT NULL,
    close_price   DECIMAL(19,4) NOT NULL,
    source        VARCHAR(32)   NOT NULL,
    fetched_at    DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_price_instrument FOREIGN KEY (instrument_id) REFERENCES instrument(id),
    CONSTRAINT uk_price UNIQUE (instrument_id, price_date),
    INDEX idx_price_date (price_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE portfolio_valuation_daily (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    portfolio_id   BIGINT        NOT NULL,
    valuation_date DATE          NOT NULL,
    market_value   DECIMAL(19,4) NOT NULL,
    cost_basis     DECIMAL(19,4) NOT NULL,
    cash_balance   DECIMAL(19,4) NOT NULL DEFAULT 0,
    unrealised_pnl DECIMAL(19,4) NOT NULL,
    CONSTRAINT fk_val_portfolio FOREIGN KEY (portfolio_id) REFERENCES portfolio(id),
    CONSTRAINT uk_val UNIQUE (portfolio_id, valuation_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**MySQL gotchas that will bite you:**

- `JdbcTemplate` generated keys need a `KeyHolder` plus
  `PreparedStatement.RETURN_GENERATED_KEYS`. Don't guess this — write it once in a base
  repository and reuse it.
- Add `?serverTimezone=UTC` (or set the session time zone) on the JDBC URL, or your
  `DATETIME` values will silently shift.
- `CHECK` constraints are enforced in MySQL 8.0.16+. Confirm your version.
- Upserting the holdings projection: `INSERT ... ON DUPLICATE KEY UPDATE`.
- Use `utf8mb4` everywhere, never `utf8`.

---

## 2. Enums (Java `enum`, persisted as `name()`, never as ordinals)

```java
AssetType       { STOCK, BOND, ETF, CASH, CRYPTO }
TransactionType { BUY, SELL, DIVIDEND, DEPOSIT, WITHDRAWAL, FEE }
CurrencyCode    { USD, EUR, GBP, INR }
PriceSource     { YFINANCE, TWELVE_DATA, MANUAL, SEED }
RebalanceAction { BUY, SELL, HOLD }
```

Every read path handles an unrecognised database value explicitly rather than throwing
`IllegalArgumentException` from `valueOf` deep inside a `RowMapper`.

---

## 3. Domain rules (agree these now, they are the tests)

| Rule | Behaviour |
|---|---|
| `BUY` | quantity added; `avg_cost` becomes the weighted average of old and new cost including fees |
| `SELL` more than held | rejected, `400`, `problem.type = /errors/insufficient-quantity` |
| `SELL` | quantity reduced; `avg_cost` unchanged; `realised_pnl += (price − avg_cost) × qty − fees` |
| `SELL` to zero | holding row deleted (or quantity 0 and filtered from responses — pick one, write it down) |
| Cash balance | `DEPOSIT` + `SELL` proceeds + `DIVIDEND` − `WITHDRAWAL` − `BUY` cost − `FEE` |
| `BUY` exceeding cash | allowed in v1, flagged as a warning in the response. Document the choice |
| Fractional quantities | allowed, 6 decimal places |
| Zero or negative quantity | rejected at validation |
| Unknown symbol | rejected, `404`, before anything is written |
| Mixed currencies in one portfolio | rejected in v1; FX is a documented limitation |
| Unrealised P&L % when cost basis is zero | return `null`, not `Infinity`, not a crash. **Write this test.** |
| Latest price missing for an instrument | fall back to the most recent `price_history` row and expose `priceAsOf` in the response |

---

## 4. API contract — `/api/v1`

All endpoints require a valid Google ID token as `Authorization: Bearer <token>` except
`/actuator/health` and the Swagger UI. Every response is scoped to the authenticated user.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/me` | current user profile, created on first sign-in |
| `GET` | `/portfolios` | list the user's portfolios |
| `POST` | `/portfolios` | create |
| `GET` | `/portfolios/{id}` | detail with summary valuation |
| `DELETE` | `/portfolios/{id}` | delete |
| `GET` | `/portfolios/{id}/transactions` | paged, filterable by type and date range |
| `POST` | `/portfolios/{id}/transactions` | record a transaction, recompute holdings |
| `DELETE` | `/portfolios/{id}/transactions/{txnId}` | delete and rebuild the projection |
| `GET` | `/portfolios/{id}/holdings` | current positions with live value and P&L |
| `GET` | `/portfolios/{id}/valuation?asOf=` | totals: market value, cost basis, cash, P&L |
| `GET` | `/portfolios/{id}/performance?from=&to=` | daily time series for the chart |
| `GET` | `/portfolios/{id}/allocation` | breakdown by asset type and sector |
| `GET` | `/instruments?query=` | symbol search for the add form |
| `GET` | `/instruments/{symbol}/prices?from=&to=` | price history |
| `POST` | `/portfolios/{id}/insights` | LLM natural-language summary *(stretch)* |
| `POST` | `/portfolios/{id}/query` | natural-language query → structured filter *(stretch)* |
| `GET` | `/portfolios/{id}/optimize?engine=classical\|quantum` | allocation optimiser *(stretch)* |
| `POST` | `/admin/prices/refresh` | trigger a price refresh |

Standard error body, RFC 9457 `ProblemDetail`, one shape everywhere:

```json
{
  "type": "https://portfolio.local/errors/insufficient-quantity",
  "title": "Insufficient quantity",
  "status": 400,
  "detail": "Cannot sell 50 AAPL; holding is 20.",
  "instance": "/api/v1/portfolios/7/transactions",
  "correlationId": "1f9c2e40-...",
  "errors": [ { "field": "quantity", "message": "must not exceed holding" } ]
}
```

Status codes: `200` read, `201` create with `Location`, `204` delete, `400` validation,
`401` missing or invalid token, `403` another user's resource, `404` not found,
`409` conflict, `422` domain rule violation, `502` upstream price provider failed,
`500` everything else — and `500` never leaks a stack trace.

---

## 5. Package layout

```
com.<org>.portfolio
├── common/          MoneyUtils, exceptions, enums, correlation-ID filter   [frozen after Day 1]
├── config/          beans, RestClient, cache, OpenAPI                       [Dev B]
├── security/        resource-server config, token→user resolution           [Dev B]
├── user/            AppUser, repository, JIT provisioning                   [Dev B]
├── marketdata/      PriceProvider, DB + HTTP impls, cache, scheduler        [Dev B]
├── instrument/      Instrument, repository, search                          [Dev A]
├── portfolio/       Portfolio, repository, service                          [Dev A]
├── transaction/     Txn, repository, service, projection rebuild            [Dev A]
├── holding/         Holding, repository, projection                         [Dev A]
├── valuation/       valuation engine, performance series, allocation        [Dev A]
├── api/             controllers, dto/, mapper/, GlobalExceptionHandler      [Dev C]
└── graphql/         schema + resolvers                                      [Dev C]
```

Inside each: `*Controller`, `*Service`, `*Repository`, `*RowMapper`, DTO records named
`Create*Request` / `*Response`.

---

## 6. Google sign-in, concretely

The lightest thing that is still correct, and it fits in half a day.

**React:** `@react-oauth/google`, wrapped in `GoogleOAuthProvider` with your client ID.
The sign-in button returns a **JWT ID token**. Store it in memory (not `localStorage` —
say why in the presentation), attach it as `Authorization: Bearer <token>` on every call.
Tokens last one hour; re-prompt silently on `401`.

**Spring:** configure as an **OAuth2 resource server** trusting Google as the issuer.

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://accounts.google.com
          audiences: ${GOOGLE_CLIENT_ID}
```

Spring fetches Google's public keys and validates signature, issuer, audience and expiry
for you. Add a filter or resolver that takes the `sub` claim, looks up `app_user`,
creates the row on first sight, and exposes the internal user ID to services. Every
repository query filters by that user ID — write one test that proves user A cannot read
user B's portfolio, because that is the test an assessor will look for.

Do **not** put the client secret in the backend; this flow doesn't need it. Do **not**
write your own JWT parsing. Do **not** trust the `email` claim without checking
`email_verified`.

*Upgrade if you have spare time:* exchange the Google token for your own short-lived JWT
plus a refresh token at `POST /api/v1/auth/session`. Better UX, more code. Note it as an
ADR either way — knowing the trade-off is what gets marked.