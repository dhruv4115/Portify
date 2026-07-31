# ADR-0012 · Collapse the five Maven modules into one

**Status:** Accepted · **Date:** Day 1 · **Owner:** Dev A · **Supersedes:** ADR-0003's
module-graph-enforcement clause only — the modular-monolith-not-microservices decision itself
is unaffected and this ADR does not revisit it.

## Context

ADR-0003 chose a 5-module Maven reactor (`portfolio-parent` → `common`, `db`, `core`,
`platform`, `api`) specifically so that `core → platform` would not compile. It weighed that
against "packages only, no Maven modules" and rejected the package-only option in these words:
*"package-only boundaries are enforced by discipline, and discipline does not survive 17:00 on
Day 5. The module graph enforces itself."* That argument is still correct on its own terms.

What changed is not the argument — it's who is asking for the trade-off and why. Partway
through Day 1, after `portfolio-common` and the Day 0 skeleton were already built, the team
(Dev A, in conversation) asked for a single `src/` tree with a conventional layered structure
instead. The reasons given were about day-to-day friction of the 5-module layout, not a
disagreement with the compile-time-boundary argument itself — the trade-off was made
knowingly, with ADR-0003's rejection of this exact alternative read back before deciding.

Three things are true at once here, and this ADR exists to keep them all visible:

1. The multi-module structure was working. `mvn clean verify` was green, the boundary was
   real, `dependency:tree -pl portfolio-core` proved it.
2. ADR-0003 explicitly evaluated and rejected this exact alternative, for a stated reason that
   remains valid in general.
3. The team chose it anyway, for this project, at this point. That is a legitimate call to
   make — it is not free, and this document is where the cost is supposed to be visible rather
   than discovered later.

## Decision

**Collapse to a single Maven module, one `pom.xml`, one `src/main/java` tree.** Packages are
feature-first and flat under `com.protify.portfolio`, matching REFERENCE_DESIGN.md §5's
original (pre-multi-module) layout: `common/`, `support/`, `config/`, `security/`, `user/`,
`marketdata/`, `fx/`, `instrument/`, `portfolio/`, `transaction/`, `holding/`, `valuation/`,
`api/`, `graphql/`. Flyway migrations move from their own module to
`src/main/resources/db/migration/`.

Ownership by area is **unchanged** — Dev A still owns `common`, `support`, and the
domain-service packages; Dev B still owns `config`/`security`/`user`/`marketdata`/`fx`; Dev C
still owns `api`/`graphql`. The Flyway version-range partitioning (A `V1`–`V9`, B `V10`–`V19`,
C `V20`–`V29`) is untouched — it never depended on the module split, only on migrations living
in one shared, add-only directory, which they still do.

**What is explicitly not preserved:** `core ↛ platform` is no longer a compiled fact. Nothing
in the build stops `transaction`, `holding`, or `valuation` code from importing a `marketdata`
or `fx` class, or an HTTP client, directly. The convention — reach market data and FX only
through `MarketDataProvider` / `FxRateProvider` in `common` — still stands, but review is now
the only thing checking it, until an ArchUnit rule exists (see Consequences).

## Consequences

**Good**

- One `pom.xml`, one version set, one thing to `git clone` and build — less ceremony for a
  project whose "team" is one developer moving through simulated roles across six days.
- No more `-pl <module>` on every Maven command; `dependency:tree` and IDE navigation see the
  whole codebase without module-boundary friction.
- The package layout now matches REFERENCE_DESIGN.md §5's original design exactly, which
  removes one layer of divergence between the two founding documents.

**Bad, and accepted**

- **The one property ADR-0003 built the module split specifically to get — a compile error on
  a wrong import — is gone.** This is not a minor footnote; it was the stated reason for the
  more expensive option. Losing it was a known, named cost at decision time, not a surprise
  found later.
- Every "do not touch: `portfolio-platform/**`" style boundary in the Day 0–6 phase prompts and
  in `/docs/PLAN.md` now means a package, not a directory a second Maven module protects. A
  developer (or an agent) can edit outside their area and the build will not say anything.
- The gap this ADR leaves is exactly the one ADR-0003 named for the "packages only" alternative
  it rejected. **Closing it is not optional cleanup — see Revisit when.**

**Neutral**

- Extraction seams (`/docs/ARCHITECTURE.md` §8) are unaffected in substance: moving `marketdata`
  and `fx` into a separate service is still "move a directory, swap an adapter," whether the
  directory currently sits in its own Maven module or not.

## Alternatives considered

| Alternative | Why not |
|---|---|
| **Keep the 5-module reactor** | Still the technically stronger choice by ADR-0003's own argument. Rejected here because the team explicitly asked to trade that guarantee for a simpler day-to-day structure, with the trade-off read back to them first |
| **Single module, layer-first packages** (`controller/`, `service/`, `repository/` cutting across every feature) | Considered and offered as an option; feature-first was chosen instead because it is the smaller diff from what Day 0/1 had already built and matches REFERENCE_DESIGN.md §5 |
| **Keep multi-module, add ArchUnit on top** | Would have kept both the compiled guarantee and a second, redundant check. Not chosen because the ask was specifically for one `src/` tree, not for belt-and-suspenders enforcement |

## Revisit when

- **Immediately, in spirit: write the `ArchitectureTest` ArchUnit rule that PLAN.md §3 and
  `/docs/PHASE_PROMPTS/day-4-dev-A.md` already scheduled for Day 4** — "`portfolio-core` must
  not import `portfolio-platform` — now enforced by your own ArchUnit rule" was always the plan
  for a *second*, redundant layer on top of the module graph. With the module graph gone, that
  rule is no longer redundant — it is the only automated check left, and moving it earlier than
  Day 4 is worth considering given that.
- A second developer (a real one) joins and the "discipline does not survive 17:00 on Day 5"
  argument starts applying to somebody who did not make this trade-off knowingly.
- Any single package area starts approaching the ~15k-line threshold ADR-0003 already names for
  splitting a module — the same signal, independent of how many Maven modules exist today.
