# RISKS.md — Protify Portfolio Manager

Scored **before** mitigation. `P` = probability, `I` = impact on the Day 6 showcase, both 1–5.
Exposure = P × I. Reviewed at every morning stand-up; a risk whose trigger has fired is an
agenda item, not a footnote.

| # | Risk | P | I | Exp | Owner |
|---|---|:-:|:-:|:-:|---|
| **R1** | Performance-series + FX computation harder than estimated | 4 | 5 | **20** | Dev A |
| **R2** | Stretch goals eat core delivery | 4 | 5 | **20** | Tech lead |
| **R3** | Multi-currency scope creep beyond the reference design | 4 | 4 | **16** | Tech lead |
| **R4** | Market-data rate limits and 429s | 4 | 4 | **16** | Dev B |
| **R5** | Three developers merging into one repo | 4 | 3 | **12** | All |
| **R6** | Google OAuth consent screen and client setup time | 3 | 4 | **12** | Dev B |
| **R7** | Docker not installed locally on every machine | 3 | 3 | **9** | Dev B |
| **R8** | Jenkins is a single VM with no fallback | 3 | 3 | **9** | Dev B |
| **R9** | Frontend under-resourced because nobody owns it | 3 | 4 | **12** | Tech lead |
| **R10** | `portfolio-common` freeze slips past Day 1 | 3 | 4 | **12** | Dev A |
| **R11** | Demo machine has no internet on the day | 2 | 5 | **10** | Dev B |
| **R12** | A developer is unavailable for a day | 2 | 4 | **8** | Tech lead |
| **R13** | MySQL `DATETIME` timezone drift corrupts date logic | 3 | 3 | **9** | Dev A |
| **R14** | LSE pence (GBX) quoting inflates a portfolio 100× | 3 | 3 | **9** | Dev B |
| **R15** | A secret reaches the repository | 2 | 5 | **10** | All |

---

## R1 · The performance-series and FX computation is harder than estimated · **20**

The single most likely technical overrun. A daily series over a mixed-currency portfolio has
to combine three sparse, misaligned datasets: transactions on trade dates, prices on trading
days, FX rates on ECB business days. Weekends, market holidays that differ per exchange
(NSE and NASDAQ do not close on the same days), and a transaction on a non-trading day all
have to produce a sensible point. Written as SQL, this is a window function over three
outer-joined series and it is very easy to get subtly wrong — off-by-one-day errors that
look plausible on a chart.

**Mitigation, already applied in the design:** the series is **not** SQL. Three flat queries
feed a pure in-memory fold (`/docs/PLAN.md` §5). This turns a hard SQL problem into an
ordinary algorithm that can be unit-tested with hand-built lists, no database, no clock. It
is the main reason the estimate is 1.5 hours rather than a day.

**Further mitigations:**
- `ProjectionEngine` is written on **Day 2**, one day before the series needs it, so the fold is proven before the date-walking is added.
- The forward-fill rule is written down before coding: carry the last known value forward, never interpolate, never look ahead. `filled: true` in the response makes it visible.
- Fixtures come from a hand-computed spreadsheet, so "the code agrees with itself" cannot pass for correct.

**Triggers → action:**
| Trigger | Action |
|---|---|
| `PerformanceService` not passing its first 3 tests by **Day 3, 14:00** | Drop `interval=WEEKLY/MONTHLY`, ship `DAILY` only |
| Not working by **Day 3, 16:00** | Ship the series with **market value only** — no cost-basis line, no `netContributions`. The chart still draws |
| Not working by **Day 4, 10:00** | Serve the series from `portfolio_valuation_daily` snapshots computed nightly; accept that today's point is yesterday's |

---

## R2 · Stretch goals eat core delivery · **20**

The failure mode of every training project: GraphQL, quantum optimisation and an LLM service
are more interesting than getting `SELL` to reject correctly, so they get done first and the
core arrives broken. The instructor is assessing a **Portfolio Manager**, not a technology
tour.

**Mitigation:**
- The quantum optimiser is **cut on Day 0, in writing** (`/docs/DECISIONS/0009-quantum-cut.md`). Not deferred — cut. A deferred item comes back at 22:00 on Day 5.
- **No stretch work before Day 5.** Day 5 is scheduled last precisely so it is the thing that gets compressed.
- Every Day 5 story is MoSCoW **Could**. `/docs/BACKLOG.md` §10.
- The MVP is tagged `v0.1-mvp` at the end of Day 3. There is always a working thing to demo.
- The "what we cut and why" slide is a **required deliverable** (`D6-C2`). Cutting well is scored; a half-finished quantum notebook is not.

**Triggers → action:**
| Trigger | Action |
|---|---|
| Anyone opens a branch for a Day 5 story before Day 5 | Tech lead closes it, same day |
| Day 4 ends with any **Must** story unfinished | Day 5 is cancelled entirely and spent on Day 4's list |
| Day 5 15:00 and GraphQL is half-built | Revert it. A merged half-feature is worse than an ADR explaining the deferral |

---

## R3 · Multi-currency scope creep · **16**

The customer's currency requirement reverses REFERENCE_DESIGN §3, which explicitly said
mixed currencies were rejected in v1. It adds an `fx_rate` table, a second provider, a second
cache, historical rate lookup and conversion in every read path — roughly 12 developer-hours,
and it touches all three developers' code. It also opens a long tail: which rate for a
weekend trade, what about triangulation error, what about a currency the provider does not
quote.

**Mitigation:**
- The boundary is written down and narrow: **four currencies** (USD, EUR, GBP, INR), **daily** rates, **USD pivot**, cross rates derived. Not "multi-currency" in general.
- Stored values stay native. Conversion is a read-time concern in exactly one layer. A currency bug therefore cannot corrupt data — it can only make a display wrong, which is recoverable.
- Cost basis at trade-date FX is scheduled as a **separable** step (PM-30, Should) so it can be dropped back to latest-rate without unpicking anything.
- `fx_rate` is seeded (`V12`) with two years of daily history, so the feature never depends on a live call.

**Triggers → action:**
| Trigger | Action |
|---|---|
| Anyone asks for a fifth currency | Refuse until Day 7. Four proves the architecture |
| FX work not complete by **Day 3, 12:00** | Execute descope ladder rung 5: cost basis at latest rate, documented as a limitation |
| Still broken by **Day 4, 10:00** | Rung 6: single-currency portfolios, revert to REFERENCE_DESIGN §3 as written, **and tell the customer that day** — not on Day 6 |

---

## R4 · Market-data rate limits and 429s · **16**

Free tiers are tight: Twelve Data allows 8 requests/minute and 800/day; Yahoo's chart
endpoint is unofficial, unversioned and can start returning 429 or a captcha page with no
notice. A demo that refreshes 18 instruments on page load will trip a limit within minutes.
Worse, a 429 arriving mid-demo is exactly when it hurts most.

**Mitigation:**
- **Nothing on a request path calls a provider.** Reads hit Caffeine, then `price_history`. Providers are called only by the scheduler and by the explicit `/admin/prices/refresh`.
- Token bucket sized to the provider's documented tier, plus exponential backoff with jitter.
- Circuit breaker: 3 consecutive failures opens for 5 minutes, serving cache throughout.
- `MarketDataProvider` is a port — swapping Yahoo for Twelve Data is a config change (ADR-0008). Two adapters are built on Day 2 so the swap is already proven.
- Two years of prices are **seeded in `V11`**, generated offline by the Python `yfinance` backfill. The demo never needs a live call.
- `RateLimitedProviderTest` simulates a 429 and asserts no 5xx escapes.

**Triggers → action:**
| Trigger | Action |
|---|---|
| Any 429 observed in development | Halve the refresh frequency that day |
| Yahoo returns non-JSON or a captcha | Flip config to Twelve Data; if no key exists, run on seeded data and say so in the demo |
| Both providers unavailable on Day 6 | Nothing to do — the demo already runs offline. This is what R11's dry-run proves |

---

## R5 · Three developers merging into one repo · **12**

Three people, one Spring context, one `pom.xml`, one migration folder, six days. The classic
outcomes are a `pom.xml` conflict every afternoon, two developers claiming `V5`, and a
Friday-night merge that breaks everything.

**Mitigation, structural rather than procedural:**
- **Directory ownership** (`CLAUDE.md`). Editing outside your area is a stop-and-ask, not a judgement call.
- **`pom.xml` has one owner (Dev A).** Others request a dependency; Dev A pushes it to `develop`. This removes the most conflict-prone file in the repo from two of three developers' hands.
- **Flyway number ranges** A `V1`–`V9`, B `V10`–`V19`, C `V20`–`V29`. Migrations are add-only, so a conflict in `portfolio-db` is impossible by construction. `FlywayMigrationIT` fails the build on a duplicate version anyway.
- **Module boundaries are compiler-enforced.** `core` cannot import `platform` because the dependency does not exist. The most damaging kind of accidental coupling cannot compile.
- Merge to `develop` by **17:30 daily**. PRs under ~400 lines. Rebase before opening.

**Triggers → action:**
| Trigger | Action |
|---|---|
| Any branch unmerged for 24 hours | Stand-up escalation; split it or merge it behind a flag |
| Two conflicts in the same file in one day | That file gets a single owner for the rest of the week |
| `develop` red for more than 60 minutes | Everyone stops feature work until it is green. No exceptions |

---

## R6 · Google OAuth consent screen and client setup time · **12**

No Google Cloud project exists yet. Creating one is usually 30 minutes, but it can become a
day: a Workspace-managed account may need admin approval to create projects, "External"
publishing status has a verification path that is slow if triggered, redirect origins are
fiddly, and an `aud` mismatch produces a 401 that looks identical to a signature failure.
Every user-scoped endpoint sits behind this.

**Mitigation:**
- Scheduled as **the very first task of Day 0** (`D0-B1`), so a blocker surfaces with a full day of slack.
- **External + Testing** publishing status with 4 named test users. This needs no verification review — the review queue is the thing that actually costs days, and Testing mode avoids it entirely.
- The client ID is not a secret; it is an env var with a documented local default, so the whole team shares one client.
- A real ID token is minted by hand on Day 0 and saved to a scratch file. Every developer can then exercise the API with `curl` before any UI exists.
- Diagnostics: `SecurityConfigTest` asserts distinct failure reasons for bad signature, wrong `aud`, wrong `iss` and expiry, so a 401 is never ambiguous.

**Triggers → action:**
| Trigger | Action |
|---|---|
| No client ID by **Day 0, 12:00** | Escalate to the instructor — this is an environment blocker, not a coding task |
| Blocked by an admin-approval queue | Use a personal Google account's Cloud project for development; production config is a one-line env change |
| Still blocked at **Day 1, 12:00** | Stand up a local mock issuer to unblock Devs A and C **for development only**, with real Google restored before the Day 1 demo. The customer asked for real auth; this is a scaffold, never the deliverable |

---

## R7 · Docker not installed locally on every machine · **9**

Downgraded from the original brief, which assumed no Docker at all. A Linux VM with Docker
is available and Docker Desktop can be installed. The residual risk is time: Desktop
installation needs admin rights on a bank-managed laptop, and Testcontainers on a VM over a
shared filesystem can be slow enough to make `mvn verify` painful.

**Mitigation:**
- Testcontainers stays as the real integration strategy (`/docs/PLAN.md` §2.4). We are not maintaining a second testing architecture for one machine.
- `mvn clean verify -DskipITs` lets anyone work all day without Docker. **It is a convenience, not the definition of green.**
- CI (GitHub Actions and Jenkins) runs the full suite on every push, so integration coverage never depends on who has Docker.
- `withReuse(true)` and one container per suite keeps the local run near 90 seconds.

**Triggers → action:**
| Trigger | Action |
|---|---|
| Admin rights refused on any machine | That developer works `-DskipITs` and relies on CI. Assign them frontend-heavy stories on Day 4 |
| `mvn verify` over 5 minutes locally | Move ITs to a `-Pit` profile, run on push only |

---

## R8 · Jenkins is a single VM with no fallback · **9**

Also downgraded — a Jenkins VM exists. The residual risk is that it is one machine: if it is
off, unreachable from the bank network, or its credentials expire on Day 6, the CI/CD
deliverable disappears on the day it is assessed.

**Mitigation:**
- **GitHub Actions is kept as a full mirror of the Jenkins stages**, not a token workflow. Two independent CIs; both must be green.
- The `Jenkinsfile` is a tracked artefact in the repo. Even with the VM down, the pipeline definition is reviewable — and a screenshot of a green run is captured on Day 4 (`D4-B3`) rather than Day 6.
- Pipeline stages are ordinary Maven and Docker commands with no Jenkins-only plugins, so the same steps run locally and in Actions.
- Final clean-workspace run happens on **Day 6 morning** (`D6-B2`), leaving the afternoon to recover.

**Triggers → action:**
| Trigger | Action |
|---|---|
| VM unreachable for over 2 hours | Continue on GitHub Actions; re-run Jenkins when it returns |
| VM dead on Day 6 | Demo the Day 4 screenshot plus a live GitHub Actions run, and say plainly which is which |
| Webhook unreliable | Switch to SCM polling every 2 minutes. Less elegant, more reliable |

---

## R9 · Frontend under-resourced because nobody owns it · **12**

The customer chose flexible frontend ownership, which is right for a small team but creates a
classic gap: work that everyone can do is work nobody schedules. Two of the customer's four
verbs — *browse* and *view performance* — are only demonstrable through the UI. A perfect
API with no chart fails the demo.

**Mitigation:**
- Frontend tasks are named, estimated and assigned per day like any other work (`D1-C5`, `D2-C5`, `D3-C4`, `D4-C2/C3`). They are not "whoever has time".
- The API contract freezes at end of Day 2 so frontend work parallelises from Day 3.
- `D3-C4` — the chart, form and delete — is on the **critical path**, marked 🔴. It cannot be quietly deprioritised.
- The evening demo is run **through the browser**, not through curl, from Day 2 onward. A missing UI is visible the same day.

**Triggers → action:**
| Trigger | Action |
|---|---|
| Day 3 17:00 and the loop does not work in a browser | Pull a second developer onto frontend for Day 4 |
| The chart is not rendering by Day 4 | Ship a table of daily values instead. Priority 2 is *view performance*, not *view a chart* |

---

## R10 · `portfolio-common` freeze slips past Day 1 · **12**

`MoneyUtils`, the enums and the two ports must be merged by end of Day 1. Two developers'
Day 2 work compiles against them. A slip does not cost a day — it costs two developer-days,
and a rushed `common` gets reopened all week, which is how rounding rules quietly diverge.

**Mitigation:**
- These are the **first four tasks** of Day 1 for Dev A (`D1-A1` … `D1-A4`), 3.75 hours total, scheduled in the morning.
- The ports are interfaces with no implementation — cheap to get right, and Dev B only needs the signature to start Day 2.
- Reviewed by all three before merge; it is the one file set everyone reads.
- Post-freeze changes need team agreement, which is a deliberate speed bump rather than a ban.

**Triggers → action:**
| Trigger | Action |
|---|---|
| Ports not merged by **Day 1, 14:00** | Dev A stops everything else and finishes them |
| Not merged by end of Day 1 | Devs B and C start Day 2 against agreed interface **signatures** written on the board; Dev A merges before 10:00 Day 2 |
| Third post-freeze change request | Stop. The abstraction is wrong; spend 30 minutes as a team fixing it properly |

---

## R11 · Demo machine has no internet on the day · **10**

Low probability, total impact. Corporate wifi, a guest network that blocks outbound HTTPS to
an unknown host, or a captive portal — any of these makes a live-provider demo fail in front
of the assessor.

**Mitigation:** this is the constraint the whole data layer was designed around.
- Two years of prices (`V11`) and FX rates (`V12`) ship **inside migrations**. A fresh database is immediately useful.
- Every external call has the fallback chain in ARCHITECTURE §7.
- `OfflineDemoIT` replaces every provider with one that always throws and asserts every read endpoint still returns 200 — automated proof, run on every build.
- **`D6-B1` is a full wifi-off dry-run of the whole demo**, on the demo machine, before the showcase.
- The one genuine dependency is Google JWKS for token validation. Mitigation is operational: sign in once at the start, and the key set is cached.

**Trigger → action:** any provider failure during the dry-run → the dry-run has done its job.
Fix the fallback, re-run. Do not "fix" it by requiring the network.

---

## R12 · A developer is unavailable for a day · **8**

Three people; losing one for a day removes 17 % of the week's capacity, and the work is
directory-partitioned, which is efficient until someone is absent.

**Mitigation:**
- Everything merges to `develop` daily, so no work is stranded on a laptop.
- `/docs/PHASE_PROMPTS/day-N-dev-X.md` is written to be picked up by **anyone**, including a fresh session with no history. That is what makes them a continuity plan and not just a convenience.
- The critical path runs through Dev A. If Dev A is out, Dev B takes `core` — the ports mean `core` has no hidden dependency on anything only Dev B understands.

**Triggers → action:**
| Trigger | Action |
|---|---|
| Absence of one day | Reallocate that day's tasks from the phase prompts; drop one **Should** story |
| Absence of two or more days | Cut to the four customer verbs only. Execute descope ladder rungs 1–3 immediately |

---

## R13 · MySQL `DATETIME` timezone drift · **9**

`DATETIME` has no timezone. If the JDBC session zone and the JVM zone disagree, values shift
silently on write and read. In a product where a transaction dated 2026-06-30 must pick up
that day's FX rate, an off-by-one-day error is a wrong number that looks entirely plausible.
It typically surfaces as one flaky test that gets re-run rather than investigated.

**Mitigation:**
- `?serverTimezone=UTC` on every JDBC URL — application, tests and Testcontainers. Flagged in REFERENCE_DESIGN §1 and enforced in `AbstractIntegrationTest`.
- JVM forced to UTC via `-Duser.timezone=UTC` in the Dockerfile and the surefire config.
- `Clock` injected everywhere, never `LocalDate.now()` in business logic, so tests can freeze time.
- A test that writes `2026-06-30T23:30:00Z`, reads it back, and asserts the same instant — the one case that catches drift.

**Trigger → action:** any date-related test failing intermittently → stop and check zones
before touching the logic. It is nearly always this.

---

## R14 · LSE pence (GBX) quoting inflates a portfolio 100× · **9**

London-listed instruments quote in pence, not pounds. A provider returning `2915` for Shell
means £29.15. Stored naively, a GBP holding is worth 100× too much, which cascades into
every total, weight and chart on the page. It is the kind of error that survives review
because the code is correct — only the unit is wrong.

**Mitigation:**
- Normalisation happens **once, in the provider adapter**, before anything is stored. `instrument.currency` is never `GBX`.
- `PriceNormalisationTest#shouldConvertGbxToGbpOnIngestion` covers it explicitly.
- Seed data for GBP instruments is hand-checked on Day 2 against a public quote — a 100× error is obvious to the eye and invisible in an assertion nobody wrote.
- A sanity assertion in `SeedDataIT`: no seeded GBP instrument has a close price over 1000.

**Trigger → action:** any GBP position looking implausible → check the unit before the maths.

---

## R15 · A secret reaches the repository · **10**

Low probability, high impact at a bank. A Google client secret, an LLM API key or a
`.env` committed in a hurry on Day 5 is a finding regardless of how good the product is —
and git history means deleting it later does not remove it.

**Mitigation:**
- `.gitignore` covers `.env`, `*.pem`, `*.key`, `application-local.yml` from Day 0.
- `.env.example` is tracked with placeholder values; the real `.env` never is.
- Every secret is an env var with a safe local default. `ConfigurationSmokeTest` fails if a config value looks like a live key.
- CI greps the diff for high-entropy strings and common key prefixes.
- `D6-B3` is a deliberate sweep of `git log -p` before the showcase.

**Trigger → action:** a secret committed → **rotate it first**, then rewrite history. In that
order. Assume anything pushed is compromised.

---

## Summary — the three that will actually bite

If only three risks get attention, these are the ones:

1. **R1 — the performance series.** Already de-risked by moving it out of SQL, but it is still the hardest code in the project and it sits on the critical path on the MVP day.
2. **R2 — stretch goals eating the core.** The most common way projects like this fail, and the only one entirely within our control. The defence is a written cut list and a rule that Day 5 work does not start early.
3. **R3 — multi-currency.** A late, customer-driven reversal of a frozen design decision. The mitigation is that it cannot corrupt stored data, so it degrades to a presentation problem rather than a data problem.

R6, R7 and R8 were the headline risks in the original brief and are now materially smaller —
the Google client is a scheduled Day 0 task with a full day of slack, and both Docker and
Jenkins turned out to be available. They stay on the register because "available" and
"working on Day 6" are different things.
