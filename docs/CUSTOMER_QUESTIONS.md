# CUSTOMER_QUESTIONS.md — open questions for the instructor

Questions we have **not** been able to answer ourselves. Each has a **working assumption** we
are already building against, so nothing is blocked — but a different answer changes work, and
the earlier we hear it the cheaper the change.

Ordered by how expensive a late answer becomes.

| Status | Meaning |
|---|---|
| 🔴 | Answer changes the plan. Ask on Day 1 |
| 🟡 | Answer changes a feature. Ask by Day 3 |
| ⚪ | Nice to confirm. Ask by Day 5 |

---

## 🔴 Q1 · Is quantum computing assessed?

We have **cut** the quantum optimiser (ADR-0009), not deferred it. Our reasoning: at
simulable portfolio sizes, QAOA is slower and approximate where brute force is instant and
exact, so it would demonstrate a Qiskit import rather than a benefit. The ~2 days it would
have cost fund the multi-currency work you asked for instead.

**Working assumption:** not assessed. We present the analysis on the "what we cut" slide.

**If you say it is assessed:** it goes back in at ~2 days, and GraphQL plus the AI insights
service come out to pay for it. **We need to know by Day 2** — after that the trade is no
longer available without hurting the core.

---

## 🔴 Q2 · Is GraphQL required, or is REST sufficient?

`REFERENCE_DESIGN` §5 lists `graphql/` as an owned directory, which reads as required. The
API table lists no GraphQL endpoints. These two signals disagree.

**Working assumption:** required. Scheduled Day 5 as a **read-only** API mirroring the REST
reads, with mutations staying on REST where the transactional and `Location`-header semantics
live. ~3 hours.

**If it is not required**, that time goes to frontend polish and analytics, which show better
in a demo.

---

## 🔴 Q3 · Who is the audience for the Day 6 showcase, and how long do we have?

It changes what we build, not just what we say. A 10-minute customer demo wants the four verbs
and a chart. A 30-minute technical review wants the ADRs, the module graph, the cross-user
test and the Jenkins pipeline.

**Working assumption:** ~10 minutes of demo plus questions, mixed audience. We are preparing
both a product walkthrough and a technical deck, with architecture and the cut-list slide held
in reserve for questions.

**Also:** do you want us to **deliberately show a failure path** — an invalid sell, an offline
price fallback? We think yes and have planned for it, but some audiences read it as a defect.

---

## 🟡 Q4 · How many currencies must genuinely be supported?

You gave USD, INR, GBP and EUR with a worked INR example. We have bounded the MVP to exactly
those four with daily ECB rates and a USD pivot (ADR-0011).

**Working assumption:** those four are sufficient to prove the architecture.

**Related, and we have made a call you may want to overturn:** cash is held **only in the
portfolio's base currency**. A USD sell credits base-currency cash at that day's rate. Real
multi-currency cash accounts (a USD balance *and* an INR balance) are a meaningful extra —
roughly a day — and we have not planned for them.

---

## 🟡 Q5 · Which instruments do you want in the seeded universe?

We seed 18 across four currencies and six asset types to prove the model is not
US-equity-only:

| Currency | Instruments |
|---|---|
| USD | AAPL, MSFT, NVDA, JPM (stocks) · SPY, QQQ (ETFs) · AGG (bond ETF) · US10Y (treasury) · BTC-USD (crypto) |
| INR | RELIANCE, TCS, HDFCBANK (stocks) · NIFTYBEES (ETF) · one mutual fund |
| GBP | SHEL, HSBA (stocks) · VUKE (ETF) |
| EUR | one stock, one ETF |

**Working assumption:** this list, two years of daily closes.

**If you want specific instruments** — something you will look for on the day — tell us by
Day 2. Adding one afterwards means regenerating a seed migration, which is a fresh database
for everyone.

---

## 🟡 Q6 · Is the frontend assessed as a deliverable, or only as a way to see the API?

It determines how much of Days 4–5 goes into React. Your clarification said all three
developers may work on it, which suggests it matters.

**Working assumption:** assessed as a working product, not as a design exercise. We build
functional, clean and responsive; we do not build a design system.

---

## 🟡 Q7 · How real should "real market data" be on the day?

We fetch live prices on a schedule and cache them, but the demo runs from data already in the
database — that is the offline-safety requirement, and we do a wifi-off dry-run (ADR-0006).

**Working assumption:** you want to see live integration *working* (a manual refresh pulling a
new price) but not the demo *depending* on it.

**Concretely:** would you like us to trigger a live `POST /admin/prices/refresh` during the
demo to show a real price arriving? We think yes, with the offline path as the fallback if the
network is unhelpful.

---

## ⚪ Q8 · Should transactions be editable, or only creatable and deletable?

`REFERENCE_DESIGN` §4 has POST and DELETE, no PUT/PATCH.

**Working assumption:** create and delete only. Delete-and-re-add reaches the same state
through one code path, and since a delete triggers a full projection rebuild, edit adds no
capability — only a second way to reach it. If you want an explicit edit for usability, it is
~2 hours on Day 5.

---

## ⚪ Q9 · Is the LLM insights feature wanted, and is a key actually available?

Planned for Day 5, feature-flagged, with a rule-based fallback that reports `engine:
RULE_BASED` so the fallback is visible rather than disguised.

**Working assumption:** wanted if time allows, not required. It is the second thing we cut
after allocation.

**We need to know:** is there a real API key, and may it be used from the bank network? If
not, we ship the rule-based version and present it honestly as such.

---

## ⚪ Q10 · Anything in the reference design you would like us to defend?

We have deviated from `/docs/REFERENCE_DESIGN.md` in five places, all recorded in
`/docs/PLAN.md` §2:

1. **Mixed currencies are now supported** — reverses §3, at your request (ADR-0011)
2. `AssetType` gains `MUTUAL_FUND` and `TREASURY` — extends §2, at your request
3. `PriceSource` gains `YAHOO`; a new `FxSource` enum — extends §2
4. Testcontainers and Jenkins are now real rather than best-effort — the environment turned out better than assumed
5. The performance series is computed in Java rather than SQL — ADR-0010, a risk decision

**One thing we disagree with and are doing anyway:** §3 allows a BUY that exceeds available
cash, returning a warning. A broker would reject it. You confirmed the warning behaviour, so
that is what we have built — flagged here so it reads as a decision rather than an oversight.

---

## Answered — kept for the record

| Question | Answer | Recorded in |
|---|---|---|
| Timeline | Day 0 setup, MVP by ~Day 3, showcase ~Day 6 | PLAN §1 |
| Hours | 6–7 per developer per day, 3 developers | PLAN §1 |
| Frontend ownership | Same team, flexible, nobody dedicated | PLAN §2.5 |
| Google OAuth | Create new project, External/Testing, **real auth, not mocked** | ADR-0004 |
| Market data provider | Not fixed — abstract behind a port, real data, cached, DB fallback | ADR-0008 |
| Multi-currency | Native storage, base-currency presentation, live FX | ADR-0011 |
| Asset model | Must support stocks, ETFs, mutual funds, bonds, treasuries, cash | PLAN §2.2 |
| Cost basis | Weighted average, leave room for FIFO | ADR-0005 |
| Sell to zero | Keep the row at quantity 0, filter from active responses | REFERENCE_DESIGN §3 |
| Mid-history delete | Full rebuild from history | ADR-0002 |
| BUY over cash | Allow with a warning | PLAN §2.7 |
| Docker / Jenkins | Both available; Testcontainers retained; Jenkins is CI of record | PLAN §2.4 |
| Coverage | 70 % line / 60 % branch, enforced once the core stabilises | TEST_PLAN §3 |
| Priority | Working end-to-end product over breadth of technology | PLAN §1 |
