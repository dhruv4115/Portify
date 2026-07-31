# Day 1 — Dev C — App shell, the error contract, `GET /me`

## You have no prior context. Read this whole file before doing anything.

You are **Dev C** on a three-person team building **Protify**, a Portfolio Management REST API
(Java 21, Spring Boot 3.5.16, MySQL 8.4, `NamedParameterJdbcTemplate` — **no JPA**) plus a
React frontend. Base package `com.protify.portfolio`.

**You own:** `portfolio-api` (controllers, `dto/`, `mapper/`, `error/`, `graphql/`), the
GitHub Actions workflow, Flyway `V20`–`V29`, and most frontend work.

**The most important thing you write today is `GlobalExceptionHandler`.** Every error in the
product flows through it for the rest of the week, and it is one of the first things an
assessor looks at.

**Read first:** `/CLAUDE.md` · `/docs/PLAN.md` §2, §3 · `/docs/API_CONTRACT.md` §0 (all of it) ·
`/docs/DECISIONS/0004-google-id-token-as-bearer.md`.

---

## D1-C1 · Application shell — 1.0 h · 🔴

`portfolio-api/…/api/PortfolioApplication.java` and `api/config/WebConfig.java`

- `@SpringBootApplication`, component scan covering `com.protify.portfolio` so `core` and `platform` beans are picked up.
- Base path `/api/v1` — set `spring.mvc.servlet.path` or use a `PathMatchConfigurer` prefix; do not repeat `/api/v1` in every `@RequestMapping`.
- Jackson: `JavaTimeModule`, `WRITE_DATES_AS_TIMESTAMPS` off, and **`BigDecimal` serialised as a plain string**. See D1-C2's note; get this right on day one and every DTO inherits it.
- Actuator health exposed.

**Test — `ApplicationContextLoadsTest`:** the context starts and `/actuator/health` returns
200 UP.

---

## D1-C2 · `GlobalExceptionHandler` — 2.5 h · 🔴 **the important one**

`portfolio-api/…/api/error/GlobalExceptionHandler.java` — one `@RestControllerAdvice`, and
**only** one.

Every response is RFC 9457 `ProblemDetail`, `application/problem+json`:

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

Handlers required:

| Exception | Status |
|---|---|
| `DomainException` (Dev A's base class) | reads `problemType()` and `status()` from the exception — **no default branch** |
| `MethodArgumentNotValidException` | 400, one `errors[]` entry per field violation |
| `ConstraintViolationException` | 400 |
| `HttpMessageNotReadableException` | 400, and the message must not echo the raw body |
| `MethodArgumentTypeMismatchException` | 400 |
| `AuthenticationException` | 401 |
| `AccessDeniedException` | 403 |
| `Exception` | 500 — **generic message only** |

**Non-negotiable:** no response body may ever contain a stack trace, a SQL fragment, or a
`com.protify` class name. The 500 handler logs the full exception with the correlation ID and
returns *"An unexpected error occurred."* and nothing more. The correlation ID is how support
finds the real error.

`correlationId` comes from the MDC key `correlationId`, set by Dev B's filter. Agree that key
with them this morning.

**A note on money serialisation that shapes every DTO you write:** money is
`{"amount": "18654.7500", "currency": "INR"}` — **the amount is a JSON string**. JavaScript
parses bare JSON numbers as IEEE-754 doubles, which cannot represent decimal money exactly, so
a number here silently corrupts the value in the browser. Configure this once in Jackson.

**Tests — `GlobalExceptionHandlerTest`, 8+ cases:** one per handler, plus a controller that
throws a deliberate `NullPointerException` — assert the body contains no `com.protify`, no
`at `, and no `SQL`. That last test is the one that matters.

---

## D1-C3 · `GET /api/v1/me` — 1.5 h · 🔴

The first real vertical slice: security filter → resolver → controller → mapper → DTO.

- `api/user/MeController.java`
- `api/dto/UserResponse.java` — a `record`, per `/docs/API_CONTRACT.md` §1
- `api/mapper/UserMapper.java` — **explicit hand-written mapping, and it is tested.** No MapStruct, no reflection.

Get the `userId` from Dev B's `CurrentUserResolver`. **No persistence type crosses this
boundary** — the controller returns `UserResponse`, never an `AppUser`.

**Tests:** `MeControllerTest` (`@WebMvcTest`) — happy path returns the profile; no token → 401
with a clean ProblemDetail. `MeControllerIT` — a real token creates the row.

---

## D1-C4 · OpenAPI — 1.0 h

`api/config/OpenApiConfig.java` — springdoc 2.8.x, a `bearer-jwt` security scheme applied
globally, Swagger UI reachable **without** authentication (Dev B has permitted the paths).

**Test — `OpenApiDocsTest`:** `/v3/api-docs` returns valid JSON containing the `/me` path and
the security scheme.

---

## D1-C5 · Frontend sign-in — 0.5 h · 🟢 can slip

In `protify-frontend`: a Google sign-in button, token held in **React state — never
`localStorage`**, an axios interceptor attaching `Authorization: Bearer <token>`, and `/me`
rendered.

You will be asked about `localStorage` in the presentation. The answer: `localStorage` is
readable by any script on the origin, so one XSS becomes account takeover; in memory, an
attacker must win while the tab is open, and the token dies on refresh. The production answer
is an httpOnly `SameSite=Strict` cookie, which needs the own-JWT exchange in ADR-0004.

---

## Rules

- **DTOs are `record`s** with Bean Validation. Persistence types never cross the controller boundary in either direction.
- **No business logic in a controller.** It validates, delegates to a service, maps the result.
- **All errors through `GlobalExceptionHandler`.** Never a `try/catch` that returns a `ResponseEntity` from a controller.
- **No `double`, no `float`.** Money is `BigDecimal` in Java and a string in JSON.
- **You may not edit `pom.xml`.** Ask Dev A.
- Status codes exactly per `/docs/API_CONTRACT.md` §0.7. In particular: **another user's resource is 404, never 403.**

## Do not touch

`portfolio-core/**` (Dev A) · `portfolio-platform/**` (Dev B) · `portfolio-common/**` — **it
freezes tonight** · any POM · migrations `V1`–`V19`.

`application.yml` is Dev B's today, by agreement — the runnable app's configuration is theirs
even though the module is yours.

---

## Done when

- [ ] App boots; `/actuator/health` returns 200 UP
- [ ] `GlobalExceptionHandler` handles all eight cases above
- [ ] A deliberate NPE returns a 500 whose body has no stack trace, no SQL, no class name
- [ ] `correlationId` in every error body, matching the `X-Correlation-Id` header
- [ ] `GET /me` works with a real Google token and returns `UserResponse`, not `AppUser`
- [ ] Swagger UI loads unauthenticated and has an Authorize button
- [ ] `BigDecimal` serialises as a JSON string — asserted by a test
- [ ] `mvn clean verify` green; merged to `develop` by 17:30

## Hand-off

Post the `problemType` slugs your handler recognises, so Dev A can match them in new exceptions.

**Tonight's demo is yours to drive:** sign in with a real Google account in the browser, show
the user row appearing in MySQL, then show a bad token returning a clean 401 ProblemDetail.

Tomorrow you build the portfolio and instrument controllers, the DTO layer, and — importantly —
**you freeze `/docs/API_CONTRACT.md` at the end of the day** (`D2-C4`). From Day 3 the whole
team codes against it, so anything ambiguous needs raising tomorrow morning, not Thursday.
