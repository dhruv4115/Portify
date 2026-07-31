# DEFINITION_OF_DONE.md — Protify Portfolio Manager

One checklist. It applies to every task in `/docs/PLAN.md` and every story in
`/docs/BACKLOG.md`. "Done" means all of it, not most of it.

> **Copy this into the PR description and tick it there.** An unticked box is a question for
> the reviewer, not a failure — but an untouched checklist means the PR is not ready.

---

## The checklist

### Code

- [ ] The acceptance criteria in the story are all satisfied — I re-read them just now
- [ ] `controller → service → repository`. No business logic in a controller or a repository
- [ ] No `JdbcTemplate` outside a `*Repository`
- [ ] All money is `BigDecimal`. **No `double`, no `float`** anywhere near a financial value
- [ ] Every `BigDecimal` comparison uses `compareTo`, never `equals` and never `==`
- [ ] All rounding goes through `MoneyUtils`. No inline `setScale` or `RoundingMode`
- [ ] Every money value has an ISO-4217 currency travelling with it
- [ ] Every user-scoped SQL statement has a `user_id` predicate — I checked each query by eye
- [ ] No user input concatenated into SQL. Named parameters only
- [ ] DTOs are `record`s with Bean Validation; no persistence type crosses the controller boundary
- [ ] Enums are persisted as `name()`; unknown DB values are handled, not thrown from a `RowMapper`
- [ ] All timestamps are UTC. No `LocalDate.now()` in business logic — the injected `Clock` instead
- [ ] No exception swallowed. Every `catch` either handles, wraps or rethrows
- [ ] Any new external call has a cache and a fallback, and the fallback is tested
- [ ] I stayed inside my own directories, or I asked in the channel first
- [ ] No new dependency in `pom.xml` unless I am Dev A, or Dev A pushed it for me

### Tests

- [ ] Happy path
- [ ] Validation failure — with the field-level error asserted
- [ ] Not found
- [ ] Unauthorised (401)
- [ ] **Cross-user (404 for another user's row)** if the endpoint touches user data
- [ ] At least one edge case from `/docs/TEST_PLAN.md` §4 if the story touches one
- [ ] Test method names read as sentences (`shouldRejectSellWhenQuantityExceedsHolding`)
- [ ] Money asserted by value, not by `equals` — `100.00` and `100.0000` must compare equal
- [ ] No test depends on the network, on wall-clock time, or on another test's ordering
- [ ] A bug fixed in this PR has a test that fails without the fix — I ran it red first

### Error handling

- [ ] Every failure path returns RFC 9457 `ProblemDetail` through `GlobalExceptionHandler`
- [ ] `type` is a real slug from `/docs/API_CONTRACT.md` §0.7, not a generic one
- [ ] `correlationId` is present and matches the `X-Correlation-Id` header
- [ ] No stack trace, no SQL fragment, no `com.protify` class name in any response body
- [ ] The right status code: 400 malformed · 401 no token · 403 forbidden · 404 missing **or another user's** · 409 conflict · 422 domain rule · 502 upstream · 500 otherwise

### Database

- [ ] Schema changes are a **new** migration in my own range (A `V1`–`V9`, B `V10`–`V19`, C `V20`–`V29`)
- [ ] I did not edit a migration that has been applied anywhere
- [ ] Money columns are `DECIMAL(19,4)`, quantities `DECIMAL(19,6)`, FX rates `DECIMAL(19,8)`
- [ ] The migration applies cleanly to an **empty** schema, not just to mine
- [ ] Indexes exist for any new query pattern
- [ ] Anything that writes a projection does so in the same transaction as its source write

### Documentation

- [ ] `/docs/API_CONTRACT.md` updated if a request or response shape changed
- [ ] An ADR added or updated if a decision was made that a future reader would question
- [ ] OpenAPI annotations present, so Swagger UI shows the endpoint correctly
- [ ] `/docs/PLAN.md` task ticked, or its slippage said out loud at stand-up
- [ ] `.env.example` updated if a new configuration key was introduced

### Build and CI

- [ ] **`mvn clean verify` is green on my machine** — the full command, not `-DskipITs`
- [ ] Coverage did not drop; new business logic is covered (gate: 70 % line / 60 % branch from Day 4)
- [ ] No new compiler warning
- [ ] GitHub Actions green on the branch
- [ ] Jenkins green, from Day 4 onward
- [ ] **No secret in the diff and none in the history.** No `.env`, key, token, client secret or password

### Review and merge

- [ ] PR under ~400 lines, or split with an explanation of why not
- [ ] Rebased on `develop`
- [ ] Conventional commit message: `feat|fix|test|docs|refactor|chore(scope): summary`
- [ ] Reviewed by a developer who does **not** own that directory
- [ ] Merged to `develop` by 17:30
- [ ] **Demonstrable in this evening's demo**, or explicitly flagged as not yet demoable

---

## Story-level extras

A story is done when every task in it is done **and**:

- [ ] It works end to end through the **UI**, not only through `curl` — if it is a user-facing story
- [ ] Someone other than the author has run it
- [ ] It survives the offline check: turn the wifi off, reload, and nothing 500s

---

## Day-level extras

A day is done when:

- [ ] `develop` is green
- [ ] The evening demo ran from a clean start, on `develop`, not from a developer's branch
- [ ] Tomorrow's phase prompts still match reality — if the plan moved, the prompts move with it
- [ ] Anything that slipped was said out loud, not silently rolled forward

---

## What "done" explicitly does not mean

- Not "it works on my machine" — it works from a clean clone
- Not "tests pass" — `mvn clean verify` passes, integration tests included
- Not "I will add the test after" — the PR is not open until the test exists
- Not "it is behind a flag so it does not matter" — a flag off is still code that gets read
- Not "the reviewer can figure it out" — if it needs explaining in Slack, it needs a comment or an ADR

---

## The three that get skipped under pressure

Every project drops the same three items when Day 5 gets late. Naming them in advance is the
only defence that has ever worked:

1. **The cross-user test.** It feels redundant once the pattern exists. It is the single test
   an assessor at an investment bank looks for first. Never skipped.
2. **The migration applying to an empty schema.** It works on your machine because your
   database already has the table from an earlier hand-run. It fails for everyone else
   tomorrow morning and costs the team an hour.
3. **`mvn clean verify` in full.** `-DskipITs` is fast, which is exactly why it gets used at
   17:25. Run the real thing before you merge.
