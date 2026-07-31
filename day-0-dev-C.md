# Day 0 — Dev C — Frontend scaffold, GitHub Actions

## You have no prior context. Read this whole file before doing anything.

You are **Dev C** on a three-person team building **Protify**, a Portfolio Management REST API
(Java 21, Spring Boot 3.5.16, MySQL 8.4) plus a React frontend. Users sign in with Google,
browse a portfolio, view its performance on a chart, and add and remove transactions.

Today is **Day 0: setup only. You write no application code** — a React scaffold that renders
a placeholder page is scaffolding, not application code.

**You own:** `portfolio-api` (controllers, DTOs, mappers, the exception handler, GraphQL), the
GitHub Actions workflow, and Flyway migrations `V20`–`V29`. You are also the team's most
frequent frontend contributor, though nobody owns the frontend exclusively.

**Read before starting:**
1. `/CLAUDE.md`
2. `/docs/PLAN.md` §1, §2, and the Day 0 table in §6
3. `/docs/API_CONTRACT.md` §0 — the conventions every endpoint follows
4. `/docs/REFERENCE_DESIGN.md` §6 — how the React side gets a token

---

## Task D0-C1 · React + Vite scaffold — 2.0 h · 🔴 critical path

Create the frontend in a **separate repository**, `protify-frontend`.

```bash
npm create vite@latest protify-frontend -- --template react-ts
cd protify-frontend
npm install
npm install @react-oauth/google axios recharts react-router-dom
npm install -D vitest @testing-library/react @testing-library/jest-dom jsdom
```

Set up:
- `src/main.tsx` wrapping the app in `<GoogleOAuthProvider clientId={import.meta.env.VITE_GOOGLE_CLIENT_ID}>`
- `.env.example` with `VITE_GOOGLE_CLIENT_ID=` and `VITE_API_BASE_URL=http://localhost:8080/api/v1`
- `.gitignore` covering `.env`, `node_modules/`, `dist/`
- A placeholder `App.tsx` — a heading and nothing else
- `vite.config.ts` with the dev server on port **5173** (Dev B has registered that origin with Google; changing it breaks sign-in)

**Two decisions to encode now, because retrofitting them is painful:**

1. **The token lives in React state, never in `localStorage`.** `localStorage` is readable by
   any script on the origin, so one XSS becomes account takeover. In memory, an attacker has
   to win while the tab is open, and the token dies on refresh. You will be asked about this
   in the presentation — the answer is in `/docs/DECISIONS/0004-google-id-token-as-bearer.md`.

2. **Money arrives from the API as a JSON *string*, not a number** —
   `{"amount": "18654.7500", "currency": "INR"}`. JavaScript parses bare JSON numbers as
   IEEE-754 doubles, which cannot represent decimal money exactly. Never call `parseFloat` on
   an amount you intend to do arithmetic with. All arithmetic happens server-side; the
   frontend formats for display only. Write the formatter helper today so nobody is tempted:

   ```ts
   export const formatMoney = (m: { amount: string; currency: string }) =>
     new Intl.NumberFormat(undefined, { style: 'currency', currency: m.currency })
       .format(Number(m.amount));   // display only — never feed this back into a calculation
   ```

**Acceptance:** `npm run dev` serves a page at `localhost:5173`.

---

## Task D0-C2 · GitHub Actions — 1.5 h

`.github/workflows/ci.yml` in the **backend** repo:

```yaml
name: CI
on:
  push:
    branches: [main, develop]
  pull_request:
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: maven
      - run: mvn -B clean verify
```

The GitHub runner has a Docker daemon, so Testcontainers integration tests run here for real.
This matters: it means integration coverage never depends on which developer has Docker
Desktop installed.

Add a step that greps the diff for high-entropy strings and common key prefixes
(`AIza`, `sk-`, `-----BEGIN`). It is cheap and `/docs/RISKS.md` R15 is a finding at a bank
regardless of how good the product is.

**Acceptance:** green on Dev A's empty skeleton. If Dev A has not pushed the POMs yet, write
the workflow and merge it once they have.

---

## Task D0-C3 · Read the API contract properly — 1.0 h

You are the person who will implement `/docs/API_CONTRACT.md`, so read it today rather than
discovering it on Day 2. Pay attention to:

- **§0.2 money as strings** — this shapes every DTO you write.
- **§0.5 `dataQuality`** — every response carrying a market value reports `priceAsOf`, `rateAsOf` and `stale`. It is how the UI shows honest, degraded data instead of failing.
- **§0.7 status codes**, especially: **another user's resource returns 404, not 403.** A 403 confirms the row exists. This is the single test an assessor at an investment bank looks for first.
- **§0.3 currency semantics** — `avgCost` and `lastPrice` are in the instrument's *native* currency; every portfolio-level total is in the *base* currency. Getting this backwards in a DTO is the easiest mistake available.

Raise anything ambiguous today. The contract freezes at the end of **Day 2** and you are the
person who freezes it (`D2-C4`).

---

## Rules you must not break

- **No backend application code today.**
- DTOs will be `record`s with Bean Validation. Persistence types never cross the controller boundary in either direction.
- All errors will go through **one** `@RestControllerAdvice` returning RFC 9457 `ProblemDetail`. No stack traces, no SQL, no `com.protify` class names in any response.
- No secrets. `VITE_GOOGLE_CLIENT_ID` is public by design and goes in `.env.example`; `.env` is git-ignored.

## What you must not touch

`pom.xml` or any module POM (Dev A owns all of them — request a dependency, do not add it) ·
`portfolio-core/**` (Dev A) · `portfolio-platform/**` (Dev B) · migrations `V1`–`V19`.

---

## Definition of done for today

- [ ] `protify-frontend` repository created, `npm run dev` serves on port 5173
- [ ] `GoogleOAuthProvider` wired with the client ID from `.env`
- [ ] `formatMoney` helper written, with a comment explaining why the amount is a string
- [ ] `.env.example` present in both repos; `.env` git-ignored in both
- [ ] `.github/workflows/ci.yml` merged and green
- [ ] Secret-scanning step in the workflow
- [ ] You have read `/docs/API_CONTRACT.md` end to end and raised any ambiguity
- [ ] You have read and agreed `/docs/PLAN.md` §2

## Hand-off

Post in the channel that the frontend scaffold and CI are up, and confirm the Vite dev server
port is **5173** so Dev B's Google origin configuration matches.

Tomorrow you build the application shell, the `GlobalExceptionHandler` (the most important
thing you write all week — every error in the product flows through it), and the first real
endpoint, `GET /me`. **You depend on Dev B's `CurrentUserResolver` and Dev A's exception
hierarchy**, both landing tomorrow. Agree with Dev A this morning that `DomainException`
carries a `problemType` slug, so your handler can map every exception without a default branch.
