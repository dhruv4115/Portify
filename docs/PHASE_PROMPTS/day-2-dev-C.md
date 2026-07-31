# Day 2 — Dev C — The browse API, the DTO layer, and freezing the contract

## You have no prior context. Read this whole file before doing anything.

You are **Dev C** on a three-person team building **Protify**, a Portfolio Management REST API
(Java 21, Spring Boot 3.5.16, MySQL 8.4, `NamedParameterJdbcTemplate` — **no JPA**) plus a
React frontend. Base package `com.protify.portfolio`.

**You own:** `api/` (controllers, `dto/`, `mapper/`, `error/`) and `graphql/`, GitHub
Actions, Flyway `V20`–`V29`, and most frontend work.

**Already merged:** Google sign-in; `GET /me`; `GlobalExceptionHandler` with RFC 9457
ProblemDetail and correlation IDs; OpenAPI. Dev A lands `ProjectionEngine`,
`PortfolioService` and `InstrumentRepository` today — **check `develop` before you start, and
if they are not there yet, build DTOs and mappers first.**

Today is **customer priority 1: browse.** It also ends with the one irreversible act of the
week — freezing the API contract.

**Read first:** `/CLAUDE.md` · `/docs/API_CONTRACT.md` §0, §2–§4, §6, §14 ·
`/docs/PLAN.md` §2.

---

## D2-C1 · The DTO layer — 1.5 h · 🔴 **do this first, it unblocks everything**

`api/dto/`

All `record`s with Bean Validation. `MoneyDto` is the one every other DTO uses:

```java
public record MoneyDto(String amount, CurrencyCode currency) { }
```

**`amount` is a `String`, not a `BigDecimal` and never a `double`.** JavaScript parses bare
JSON numbers as IEEE-754 doubles, which cannot represent decimal money exactly — so a JSON
number silently corrupts the value between your server and the browser. This is
`/docs/API_CONTRACT.md` §0.2 and it shapes every DTO you write this week.

Also today: `CreatePortfolioRequest`, `PortfolioResponse`, `InstrumentResponse`,
`DataQualityDto`, `PageResponse<T>`.

**`DataQualityDto { LocalDate priceAsOf, LocalDate rateAsOf, boolean stale }`** goes on every
response carrying a market value. It is how the UI shows honest, degraded data instead of
failing — Dev B's caching services return these dates.

**Currency semantics, and getting this backwards is the easiest mistake available:**
`avgCost` and `lastPrice` are in the **instrument's native** currency; every portfolio-level
total is in the portfolio's **base** currency. `/docs/API_CONTRACT.md` §0.3.

`unrealisedPnlPct` is `String | null` — **`null` when cost basis is zero.** Not `0`, not
`"Infinity"`. It happens on every empty portfolio, so you will see it today.

**Tests — `MoneySerializationTest`:** `MoneyDto` serialises `amount` as a JSON string; a
`BigDecimal` anywhere in a response never serialises as a JSON number; `null`
`unrealisedPnlPct` serialises as literal `null`, not omitted and not `"null"`.

---

## D2-C2 · `PortfolioController` — 2.0 h · 🔴

`api/portfolio/PortfolioController.java` + `api/mapper/PortfolioMapper.java`

| | | |
|---|---|---|
| `GET` | `/portfolios` | 200, `[]` when empty — **never 404** |
| `POST` | `/portfolios` | 201 + `Location: /api/v1/portfolios/{id}` |
| `GET` | `/portfolios/{id}` | 200, detail with summary valuation |
| `DELETE` | `/portfolios/{id}` | 204 |

Get `userId` from Dev B's `CurrentUserResolver` and pass it into every service call. **No
controller may query without it.**

**Another user's portfolio returns 404, not 403.** A 403 confirms the row exists. This is the
single most-looked-for behaviour in an assessment at an investment bank
(`/docs/API_CONTRACT.md` §0.7).

`PortfolioMapper` is **explicit, hand-written and tested**. No MapStruct, no reflection, no
`BeanUtils.copyProperties`.

**Tests — `PortfolioControllerTest` (`@WebMvcTest`), 16 cases** — for each of the four
endpoints: happy path, validation failure, not found, unauthorised. Plus: duplicate name → 409;
blank name → 400 with `errors[0].field == "name"`; unknown currency → 400; empty list → `200 []`;
**empty portfolio → `unrealisedPnlPct` is `null`, not a 500.**

---

## D2-C3 · `InstrumentController` — 1.0 h · 🔴

`GET /instruments?query=&assetType=&currency=&limit=` — the type-ahead for tomorrow's add form.

Not user-scoped; the catalogue is shared. `@NotBlank`, `@Size(1,50)` on `query`; `@Max(50)` on
`limit`, default 20.

**Tests:** `"rel"` returns RELIANCE first; blank query → 400; no match → `200 []` (a search
finding nothing is not an error); limit capped at 50; no token → 401.

---

## D2-C4 · **Freeze `/docs/API_CONTRACT.md`** — 0.5 h · 🔴

End of day. Walk the document against what actually shipped, field by field, and reconcile:
if the code differs from the doc, one of them is wrong — decide which and fix it.

Fill in the Day 2 row of the §20 changelog. **Announce the freeze in the channel.** From
tomorrow the whole team codes against it and changes need agreement.

Pay particular attention to the shapes you have **not** built yet but the team codes against
tomorrow: `TransactionResponse` (§7), `HoldingResponse` (§10), `PerformanceResponse` (§12).
Read them now and raise anything ambiguous **this morning**, not tomorrow afternoon.

---

## D2-C5 · Frontend — portfolio list — 1.5 h

In `protify-frontend`:
- `src/api/client.ts` — axios with the Bearer interceptor and a **single** retry on 401 (re-prompt silently, then give up; an infinite retry loop on an expired token is the classic bug here).
- `src/pages/Portfolios.tsx` — list, create form, empty state.
- Use the `formatMoney` helper from Day 0. **Never `parseFloat` an amount you intend to calculate with** — all arithmetic is server-side; the frontend formats for display only.

**Acceptance:** create a portfolio in the browser and see it in the list.

---

## Rules

- **DTOs are `record`s** with Bean Validation. Persistence types never cross the controller boundary in either direction.
- **No business logic in a controller.** Validate, delegate, map.
- **All errors through `GlobalExceptionHandler`.** Never `try/catch` returning a `ResponseEntity`.
- **No `double`, no `float`.** Money is `BigDecimal` in Java, a **string** in JSON.
- **`common/` is frozen.**
- **You may not edit `pom.xml`.** Ask Dev A.
- Status codes exactly per `/docs/API_CONTRACT.md` §0.7.
- Every user-scoped call passes `userId`.

## Do not touch

`instrument/`, `portfolio/`, `transaction/`, `holding/`, `valuation/`, `support/` (Dev A) · `config/`, `security/`, `user/`, `marketdata/`, `fx/`, `insights/` (Dev B) · `common/` ·
any POM · migrations `V1`–`V19`.

---

## Done when

- [ ] `MoneyDto` serialises `amount` as a JSON string, asserted by a test
- [ ] Four portfolio endpoints, with all four test types each
- [ ] Another user's portfolio returns **404**, not 403
- [ ] Empty portfolio returns 200 with `unrealisedPnlPct: null`
- [ ] Instrument search returns RELIANCE first for `"rel"`
- [ ] Mappers are hand-written and tested
- [ ] **`/docs/API_CONTRACT.md` frozen and the freeze announced**
- [ ] Portfolio created in the browser
- [ ] `mvn clean verify` green; merged to `develop` by 17:30

## Hand-off

**Tonight's demo is yours:** in the browser, sign in, create "Growth" with base currency INR,
search for RELIANCE, and show a real INR price arriving from Dev B's provider.

Tomorrow is the **MVP day** — performance, add and remove, the customer's remaining three
verbs. You build `TransactionController`, `HoldingController`, `ValuationController`,
`PerformanceController` and the React chart. It is the heaviest day of the week and the chart
(`D3-C4`) is on the critical path, so start the frontend work before the last controller is
polished. A working chart with three endpoints beats four perfect endpoints and no chart.
