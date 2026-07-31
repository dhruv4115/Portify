# ADR-0003 · Modular monolith plus one Python service, not a microservices estate

**Status:** Accepted, partially superseded by [ADR-0012](0012-single-module.md) (Day 1) —
the module-graph-enforcement clause under "Decision" only; the modular-monolith call itself
stands · **Date:** Day 0 · **Owner:** Tech lead · **Supersedes:** —

## Context

The obvious "enterprise" answer for a bank training project is a Spring Cloud estate: an API
gateway, Eureka for discovery, a config server, and services split by domain — portfolio,
transaction, market data, valuation, user. It demonstrates a lot of technology and looks
impressive on an architecture slide.

The facts on the ground:

- **3 developers, 6 days.** One deployable, one database, one instructor.
- Load is one demo session. There is no scaling requirement, and there will not be one.
- The core write path — append a transaction and recompute the projection — must be
  **atomic** (ADR-0002). Split `transaction` and `holding` into separate services and that
  single `@Transactional` becomes a saga with compensating actions, an outbox table and a
  reconciliation job. That is days of work to reproduce a guarantee MySQL gives us free.
- Every service added multiplies the failure modes a live demo can hit. A gateway that has
  not warmed up, a service that has not registered with Eureka, a config server that is down
  — each is a way to fail in front of the assessor for reasons unrelated to the product.

There is one genuine case for a separate process: the AI insights component is **Python**.
That is a language boundary, not an architectural preference, and language boundaries are the
one reason to split that does not need justifying.

## Decision

**Build a modular monolith in Java — one deployable, one database — plus exactly one separate
Python FastAPI service for AI insights.**

**Explicitly not building:** API gateway, Eureka / service discovery, Spring Cloud Config,
distributed tracing infrastructure, a service mesh, or per-domain services.

The modularity is real, and it is enforced by the **Maven module graph**, not by convention:

```
portfolio-api  →  portfolio-core, portfolio-platform, portfolio-common
portfolio-core →  portfolio-common          (never platform)
portfolio-platform → portfolio-common
```

`portfolio-core` cannot import `portfolio-platform` because the dependency does not exist.
Core needs prices and FX rates; it declares `MarketDataProvider` and `FxRateProvider`
interfaces in `common` and Spring wires the adapters at runtime. **A developer who tries to
call an HTTP client from the valuation engine gets a compile error, not a review comment.**

Extraction seams are named in `/docs/ARCHITECTURE.md` §8 with an estimated cost and a trigger
for each: market data + FX (~1 day), valuation (~2 days), GraphQL BFF (~0.5 day), insights
(already done).

## Consequences

**Good**

- The atomic write stays a `@Transactional` annotation rather than a saga.
- One thing to build, one to deploy, one to debug. `docker compose up` brings up the whole
  product in two containers.
- Local development needs a JVM and a MySQL, not eight terminals.
- Refactoring across module boundaries is a compiler-checked operation.
- The module graph makes the seams **structural**. Extraction is moving a directory and
  swapping an adapter, not untangling a ball of imports.

**Bad, and accepted**

- Everything scales together. Irrelevant here; would matter with real traffic.
- One JVM is one blast radius. Mitigated by the fallback chains in ARCHITECTURE §7 — a market
  data outage degrades to cached prices rather than taking anything down.
- **We do not get to tick "microservices" on a technology checklist.** This is the real cost,
  and it is why the ADR exists: the defence is that knowing when *not* to distribute is the
  more senior judgement, and the seams document proves we know how we would.

**Neutral**

- The insights service being Python means we still demonstrate cross-service HTTP, a
  feature-flagged client, timeouts and a fallback — the interesting parts of distributed
  systems — without eight of them.

## Alternatives considered

| Alternative | Why not |
|---|---|
| **Full Spring Cloud estate** | ~2 developer-days on infrastructure that serves one user, plus four new demo failure modes, plus a saga to replace one database transaction. It buys a checklist tick and costs a working product |
| **Two services: API + market data** | The most defensible split, and it is exactly extraction seam 1. Still not worth it now: the ports already give us the boundary, and a second deployable costs compose config, health checks and a client for zero benefit at this scale |
| **Modular monolith with packages only, no Maven modules** | Cheaper to set up. Rejected because package-only boundaries are enforced by discipline, and discipline does not survive 17:00 on Day 5. The module graph enforces itself |
| **Serverless functions** | Cold starts, no persistent connection pool, no local story that resembles production |
| **Insights inside the Java monolith** | Would mean no Python, and the customer specified a Python AI service. It is also the one boundary where a separate process is genuinely justified |

## Revisit when

- A second team needs to deploy independently — the strongest real reason to split.
- Market data refresh starts competing with request traffic for resources (seam 1's trigger).
- A second client, e.g. mobile, needs a different response shape (seam 3's trigger).
- Any single module exceeds ~15k lines, which usually means it was two things all along.
