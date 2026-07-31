# Day 5 — Dev B — The Python insights service (buffer day)

## You have no prior context. Read this whole file before doing anything.

You are **Dev B** on a three-person team building **Protify**, a Portfolio Management REST API
(Java 21, Spring Boot 3.5.16, MySQL 8.4, `NamedParameterJdbcTemplate` — **no JPA**) plus a
React frontend. Base package `com.protify.portfolio`.

**You own:** `config/`, `security/`, `user/`, `marketdata/`, `fx/`, `insights/` (Java client), `scripts/`, `services/insights/` (Python), `docker/`, `Jenkinsfile`,
Flyway `V10`–`V19`.

**Status:** MVP Day 3. Docker, compose and a real green Jenkins pipeline Day 4. Market data and
FX are cached, rate-limited, circuit-broken and offline-safe.

---

## ⚠️ Read this before anything else

**Day 5 is the schedule buffer. Every task below is MoSCoW *Could*.**

- [ ] Is `develop` green — `mvn clean verify` in full?
- [ ] Does `docker compose up` work from a **clean volume**?
- [ ] Did a real **push** produce a green Jenkins run, and do you have the screenshot?
- [ ] Do reads survive the wifi being off?
- [ ] Are all ten edge cases in `/docs/TEST_PLAN.md` §4 green?

**If any box is unticked, close it today and skip everything below.** `/docs/RISKS.md` R2 — a
stretch goal that eats the core is the most common way projects like this fail, and today is
when it happens.

---

## D5-B1 · FastAPI insights service — 3.0 h · 🟢

`services/insights/` — `main.py`, `requirements.txt`, `Dockerfile`, `test_insights.py`

```
POST /insights
  { portfolioSummary: {...}, horizon: "1M", tone: "concise" }
→ { summary: "...", highlights: [...], engine: "LLM" | "RULE_BASED" }
```

**Build the fallback first, before you touch the LLM.** The rule-based generator is the part
that must work: concentration (any holding over 40 % of market value), FX exposure (share of
non-base-currency holdings), best and worst performer, cash drag. Pure Python, deterministic,
no network.

Then add the LLM path: send the **portfolio summary only** — never transaction-level data, and
never anything resembling a credential — with a 2-second timeout. On any failure, fall back to
the rule-based generator.

**`engine` in the response reports which path actually produced the text.** Not a hidden
fallback — a visible one. Being able to say *"the LLM is unavailable, so this is the rule-based
summary, and here is how you can tell"* is worth more at assessment than a summary that
silently degrades.

The API key comes from an env var. No key ⇒ rule-based only, and the service still starts.

**Tests — `test_insights.py`:** rule-based output is deterministic; no key ⇒ `RULE_BASED`; LLM
timeout ⇒ `RULE_BASED`; concentration highlight fires above 40 %; the payload sent upstream
contains no transaction-level detail.

---

## D5-B2 · Java client — 2.0 h · 🟢

`insights/InsightsClient.java`

Behind `FEATURES_INSIGHTS_ENABLED`, default **false**.

- Flag off ⇒ Dev C's endpoint returns **501** with a clean `ProblemDetail`. Not 404 — the frontend must be able to tell "turned off" from "wrong URL" (`/docs/API_CONTRACT.md` §18).
- 2-second timeout; on failure, a **rule-based summary generated in Java**, so the whole feature degrades twice before it fails.
- Never a 500. Never a blocked page.

**Test — `InsightsClientTest`:** flag off ⇒ not called; service down ⇒ rule-based text returned,
no exception escapes; timeout honoured.

---

## D5-B3 · Compose and pipeline — 1.5 h · 🟢

Add the insights service to `docker/compose.yml` (its own healthcheck) and a build stage to the
`Jenkinsfile`.

**Acceptance:** one `docker compose up` brings up MySQL, the API and insights, and the API
works normally with insights **stopped**. Test that explicitly — a compose file where one
optional service being down breaks the product is a worse deliverable than not having the
service.

---

## Rules

- **No secret in the repository.** LLM key from an env var, `.env` git-ignored.
- **Never send credentials, tokens or raw transaction data to an external LLM.** Summary-level aggregates only.
- **The demo must work with no internet.** The rule-based path is the primary deliverable; the LLM is the enhancement.
- `common/` is frozen. **You may not edit `pom.xml`** — ask Dev A.
- No `double`, no `float`. No JPA. No `JdbcTemplate` outside a `*Repository`.
- Migrations only in `V10`–`V19`.
- **Nothing merges after 17:00 today.**

## Do not touch

`instrument/`, `portfolio/`, `transaction/`, `holding/`, `valuation/`, `support/` (Dev A) · `api/`, `graphql/` (Dev C) · `common/` ·
any POM · migrations `V1`–`V9`, `V20`+.

---

## Done when

- [ ] Either: all three tasks shipped — **or** Days 1–4 gaps closed and this list consciously skipped
- [ ] The rule-based path works with **no key and no network**
- [ ] `engine` honestly reports `LLM` or `RULE_BASED`
- [ ] Flag off ⇒ 501 with a clean ProblemDetail, never 404, never 500
- [ ] `docker compose up` brings up all three services, and the API works with insights stopped
- [ ] Jenkins still green
- [ ] `mvn clean verify` green; **merged by 17:00**

## Hand-off

Tomorrow morning you do the **offline dry-run**: wifi off, whole demo start to finish, on the
demo machine. This is `/docs/RISKS.md` R11 and it is the single most valuable hour of the week.
Every screen must render; `priceAsOf` and `rateAsOf` will visibly go stale, which is correct
and worth pointing at during the demo.

Also tomorrow: a final clean-workspace Jenkins run, and a secret sweep of `git log -p` and
`git grep` for keys, tokens and client secrets before anything is shown to anyone.

**Tomorrow is feature freeze at midday.** Decide tonight what you are not going to finish.
