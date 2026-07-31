# Day 6 — Dev B — The offline dry-run, final pipeline, secret sweep

## You have no prior context. Read this whole file before doing anything.

You are **Dev B** on a three-person team building **Protify**, a Portfolio Management REST API
(Java 21, Spring Boot 3.5.16, MySQL 8.4, `NamedParameterJdbcTemplate` — **no JPA**) plus a
React frontend. Base package `com.protify.portfolio`.

**You own:** `portfolio-platform`, `scripts/`, `services/insights/`, `docker/`, `Jenkinsfile`,
Flyway `V10`–`V19`.

**Status:** MVP Day 3. Docker, compose and a real green Jenkins pipeline Day 4. Insights
service Day 5, if Day 5 was not spent on overflow.

> ## 🔒 Feature freeze at midday
> Nothing merges after 13:00 except a fix for a demo-blocking bug.

**Your first task this morning is the single most valuable hour of the week.**

---

## D6-B1 · The offline dry-run — 1.5 h · 🔴 **DO THIS FIRST**

**Turn the wifi off on the demo machine. Run the entire demo, start to finish.**

Everything the team has built around fallbacks (`/docs/ARCHITECTURE.md` §7, ADR-0006,
ADR-0008) exists for this hour. `/docs/RISKS.md` R11 is low probability and total impact —
a captive portal or a blocked outbound host on the day and there is no demo.

Work through `/docs/DEMO_SCRIPT.md` with the network genuinely off:

- [ ] Sign in — **note:** Google JWKS is the one dependency with no offline story. Sign in **before** disconnecting; the key set is cached and the token stays valid for its hour. Plan the demo so sign-in happens first, and say so if asked. A token whose signature cannot be verified *must* be rejected, and that is correct behaviour rather than a gap.
- [ ] Portfolio list renders
- [ ] Holdings render with prices from `price_history`
- [ ] `priceAsOf` and `rateAsOf` show older dates and the **stale badge appears** — this is correct, and worth pointing at during the demo rather than apologising for
- [ ] The performance chart renders in full
- [ ] Allocation renders
- [ ] Adding a transaction works
- [ ] Deleting a transaction works, and the chart moves
- [ ] The base-currency switcher works
- [ ] Insights fall back to the rule-based path, reporting `engine: RULE_BASED`
- [ ] **Nothing anywhere returns a 5xx**

Any failure here is the dry-run doing its job. Fix the fallback and re-run.
**Do not "fix" anything by requiring the network.**

---

## D6-B2 · Final Jenkins run — 1.0 h · 🔴

- `cleanWs()`, then a **push-triggered** run from a clean workspace. Not a manually-started build — the customer described a push-triggered CI/CD flow and that is what should be demonstrated.
- Confirm every stage green: build, unit, integration (Testcontainers), coverage, package, docker build, archive.
- JaCoCo trend visible in the Jenkins UI.
- Screenshot it. You should already have a Day 4 screenshot; take a second one today.
- Confirm GitHub Actions is also green — it is the fallback if the VM is unreachable this afternoon (`/docs/RISKS.md` R8).

**If the Jenkins VM is down and cannot be recovered:** demo the Day 4 screenshot plus a live
GitHub Actions run, **and say plainly which is which**. An honest "the VM is down, here is the
pipeline definition and here is the same pipeline running in Actions" is fine. Passing off a
screenshot as live is not.

---

## D6-B3 · Secret sweep — 1.0 h · 🔴

At a bank this is a finding regardless of how good the product is, and git history means
deleting a secret later does not remove it.

```bash
git log -p | grep -iE "api[_-]?key|secret|password|token|BEGIN (RSA|PRIVATE)"
git grep -iE "AIza|sk-|-----BEGIN"
git log --all --name-only | grep -E "\.env$|\.pem$|\.key$"
```

- [ ] No `.env` ever committed — check the **whole history**, not just `HEAD`
- [ ] No LLM key, no database password, no client secret
- [ ] `.env.example` has placeholders only
- [ ] No secret in `compose.yml`, the `Dockerfile` or the `Jenkinsfile`
- [ ] `docs/` still git-ignored as agreed; `/CLAUDE.md` at the root **is** tracked

**If you find one: rotate it first, then rewrite history. In that order.** Anything pushed is
compromised.

---

## Afternoon · Support the demo

**Be ready to answer, without looking anything up:**

- **Is the market data real?** Yes — real closes from a real provider, backfilled by Python and refreshed on a schedule. The demo runs from the database on purpose, so it cannot fail because of a network. `source` on each price row shows exactly where it came from.
- **What happens when the provider is down?** Cache → `price_history` → seeded rows. The user sees a `priceAsOf` date and a stale badge, never an error. Show the offline path live if you can — it is more convincing than describing it.
- **How do you handle rate limits?** Nothing on a request path calls a provider. Token bucket, exponential backoff with jitter, circuit breaker that opens after three failures and serves cache for five minutes.
- **Why is there no API gateway?** One deployable, three developers, six days. The seams are documented with costs and triggers in `/docs/ARCHITECTURE.md` §8 (ADR-0003).
- **How would you deploy this properly?** The image is already stateless and non-root; add a managed MySQL, secrets from a vault rather than env vars, and the same Jenkins pipeline pushing to a registry.

Have the Jenkins pipeline page and a terminal with `docker compose ps` open.

---

## Rules

- **Feature freeze at 13:00.**
- `portfolio-common` is frozen. Migrations only in `V10`–`V19`. **You may not edit `pom.xml`.**
- No `double`, no `float`. No JPA. No `JdbcTemplate` outside a `*Repository`.
- **Do not refactor anything today.**

## Do not touch

`portfolio-core/**` (Dev A) · `portfolio-api/**` (Dev C) · `portfolio-common/**` ·
any POM · migrations `V1`–`V9`, `V20`+.

---

## Done when

- [ ] **The full demo runs with the wifi off, no 5xx anywhere**
- [ ] Stale badges appear and are correct
- [ ] A push-triggered Jenkins run is green from a clean workspace, screenshotted
- [ ] GitHub Actions green
- [ ] Secret sweep clean across the **whole history**
- [ ] `docker compose down -v && docker compose up --build` works from nothing
- [ ] You can answer the five questions above without notes
