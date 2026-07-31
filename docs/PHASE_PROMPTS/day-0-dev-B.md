# Day 0 — Dev B — Google OAuth client, Docker, Jenkins

## You have no prior context. Read this whole file before doing anything.

You are **Dev B** on a three-person team building **Protify**, a Portfolio Management REST API
(Java 21, Spring Boot 3.5.16, MySQL 8.4) plus a React frontend. Users sign in with Google,
browse a portfolio, view its performance on a chart, and add and remove transactions.

Today is **Day 0: setup only. You write no application code.**

**You own:** `portfolio-platform` (security, user, market data, FX, config), `scripts/`,
`docker/`, the `Jenkinsfile`, and Flyway migrations `V10`–`V19`.

**Your first task is the single largest schedule risk in the project.** Every user-scoped
endpoint sits behind Google authentication, and no Google Cloud project exists yet. Start with
D0-B1 and do not start anything else until a client ID exists.

**Read before starting:**
1. `/CLAUDE.md`
2. `/docs/PLAN.md` §1, §2, and the Day 0 table in §6
3. `/docs/REFERENCE_DESIGN.md` §6 — Google sign-in, concretely
4. `/docs/DECISIONS/0004-google-id-token-as-bearer.md`
5. `/docs/RISKS.md` R6, R7, R8

---

## Task D0-B1 · Google Cloud project + OAuth client — 2.0 h · 🔴 START HERE

1. Create a Google Cloud project (name it `protify-portfolio`).
2. Configure the **OAuth consent screen**:
   - User type **External**, publishing status **Testing**.
   - Add **4 test users**: the three developers and the instructor.
   - Scopes: `openid`, `email`, `profile`. Nothing more — extra scopes trigger verification.
   - **Do not submit for verification.** Testing mode needs no review, and the review queue is the thing that actually costs days.
3. Create an **OAuth 2.0 Client ID**, type **Web application**:
   - Authorised JavaScript origins: `http://localhost:5173`, `http://localhost:3000`
   - Authorised redirect URIs: `http://localhost:5173`
4. **Obtain a real ID token by hand** and save it to a scratch file outside the repo. Use the
   Google OAuth Playground, or a five-line HTML page with the Google Identity Services button.
   Share it with Devs A and C.

That token is what unblocks everyone. It lets all three developers exercise the API with
`curl` before any UI exists.

**The client ID is not a secret** — it is public by design and goes in `.env.example`. The
client **secret** is not needed for this flow; do not put it in the backend.

**Acceptance:** a client ID exists, and a real Google ID token has been decoded (jwt.io) to
confirm it has `sub`, `email`, `email_verified`, `aud` matching your client ID, and `iss` =
`https://accounts.google.com`.

**If you are blocked by an admin-approval queue by 12:00, escalate to the instructor** — this
is an environment blocker, not a coding task. Fallback: use a personal Google account's Cloud
project for development; the production config is a one-line env change.

---

## Task D0-B2 · Docker — 1.5 h

- Verify the Linux VM's Docker daemon is running and reachable.
- Install Docker Desktop locally where permitted (bank-managed laptops may need admin rights — start the request today if so).
- Prove it: `docker run --rm hello-world`, then `docker run --rm -e MYSQL_ROOT_PASSWORD=x mysql:8.4 --version`.

We are keeping **Testcontainers** as the real integration-test strategy (`/docs/PLAN.md` §2.4).
`mvn clean verify -DskipITs` exists for anyone without a daemon, but it is a convenience, not
the definition of green — CI runs the full suite.

**Acceptance:** at least 2 of 3 machines can run Testcontainers. Record in `/docs/DEV_SETUP.md`
which machines can and cannot.

---

## Task D0-B3 · Jenkins — 1.5 h

- Confirm the Jenkins VM is reachable from all three machines.
- Get credentials for each developer.
- Configure a pipeline job pointing at the repository, with a GitHub webhook. If the webhook cannot reach the VM (likely on a corporate network), fall back to **SCM polling every 2 minutes** — less elegant, more reliable.
- Confirm Jenkins can clone the repo and has a JDK 21 and Maven available (a tool installation or a Docker agent, whichever the VM supports).

**Do not write the `Jenkinsfile` today** — that is `D4-B3`. Today is only proving the VM works
and can reach the repository.

**Acceptance:** a trivial pipeline (`echo hello` + `git log -1`) runs green on the VM.

---

## Task D0-B4 · Python environment — folded in from D0-C3, 0.5 h if time

Python 3.11 venv, `pip install yfinance requests`, and prove one fetch:

```python
import yfinance as yf
print(yf.Ticker("AAPL").history(period="5d"))
print(yf.Ticker("RELIANCE.NS").history(period="5d"))   # NSE
print(yf.Ticker("SHEL.L").history(period="5d"))        # LSE — note: quotes in PENCE
```

**Note the LSE result carefully.** London instruments quote in GBX (pence), so Shell comes back
around `2915`, meaning £29.15. On Day 2 you will divide by 100 at ingestion. Storing it raw
makes a GBP portfolio 100× too valuable — `/docs/RISKS.md` R14.

---

## Rules you must not break

- **No application code today.**
- **No secret in the repository.** Client ID goes in `.env.example` (it is public). Any API key is an env var with a local default, and `.env` is git-ignored.
- **Real Google authentication, not a mock.** The customer asked for it explicitly. A local mock issuer is a development scaffold only, allowed under the R6 fallback, and never the deliverable.

## What you must not touch

`pom.xml` or any module POM (Dev A owns all of them — request a dependency, do not add it) ·
`portfolio-core/**` (Dev A) · `portfolio-api/**` (Dev C) · migrations `V1`–`V9` or `V20`+.

---

## Definition of done for today

- [ ] Google Cloud project exists, consent screen External/Testing, 4 test users added
- [ ] OAuth 2.0 Web client ID created, origins configured
- [ ] **A real Google ID token obtained and shared with the team**
- [ ] Token decoded and its `aud`, `iss`, `sub`, `email_verified` claims confirmed
- [ ] Docker verified on the VM; Desktop installed or the admin request raised
- [ ] Jenkins reachable, credentials issued, trivial pipeline green
- [ ] Python venv with `yfinance`, one successful fetch each from NASDAQ, NSE and LSE
- [ ] `/docs/DEV_SETUP.md` updated with your sections
- [ ] You have read and agreed `/docs/PLAN.md` §2

## Hand-off

Post the client ID and the sample token location in the channel.

Tomorrow you build the OAuth2 resource server and just-in-time user provisioning. **You depend
on Dev A landing `MarketDataProvider` and `FxRateProvider` in `portfolio-common` by end of
Day 1** — your entire Day 2 compiles against them. If you want input on those signatures,
tomorrow morning is the moment. Ask for: `Optional<PriceQuote> latestPrice(String symbol)` and
`List<PriceQuote> dailyCloses(String symbol, LocalDate from, LocalDate to)`.
