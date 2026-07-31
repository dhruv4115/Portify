# ADR-0009 · The quantum optimiser is cut, not deferred

**Status:** Accepted · **Date:** Day 0 · **Owner:** Tech lead · **Supersedes:** —

## Context

The original scope listed `GET /portfolios/{id}/optimize?engine=classical|quantum` as a
stretch goal: portfolio allocation optimisation with a quantum backend, presumably QAOA or
VQE on a simulator via Qiskit.

It is worth being precise about what such a feature would actually be.

Portfolio optimisation in the Markowitz sense minimises `wᵀΣw − λμᵀw` subject to the weights
summing to one. Continuous and convex, it is solved exactly by quadratic programming in
milliseconds. To make it a quantum problem you must first make it *discrete* — typically
"pick K assets from N" — which turns it into a QUBO. That reformulation is what quantum
algorithms address, and it is a harder, coarser problem than the one we started with.

Then, on a simulator:

- A QAOA circuit over N assets needs roughly N qubits. Classical simulation is exponential, so
  the practical ceiling is ~20 assets — well under a real portfolio.
- QAOA is a **heuristic**. It returns a good solution, not a proven optimum.
- For the portfolio sizes we can simulate, brute force enumerates every subset faster and
  returns the exact answer.

So on our hardware, for our data, the quantum path is slower, approximate, and solves a
degraded version of the problem. It would demonstrate that we can call Qiskit. It would not
demonstrate that quantum computing helped.

Against that: ~2 developer-days for the optimiser, the QUBO encoding, the Qiskit dependency,
and a plausible covariance estimate — which itself needs a returns matrix we do not otherwise
compute. Two days is a third of one developer's week, and it sits on nobody's priority list.
The customer's four priorities are browse, view performance, add, remove.

## Decision

**The quantum optimiser is cut. Not deprioritised, not marked stretch, not left in the
backlog — cut, on Day 0, in writing.**

- `GET /portfolios/{id}/optimize` is removed from the API contract. Not routed, not stubbed,
  not returning 501.
- No Qiskit dependency enters `pom.xml` or any `requirements.txt`.
- `/docs/BACKLOG.md` §11 lists it under **Won't** with a link here.
- The Day 6 presentation includes it on the "what we cut and why" slide, with this reasoning.

**A deferred item is not cut.** Deferred items come back at 22:00 on Day 5 when someone has
energy and a half-formed idea, and they take the evening that was meant for the demo
rehearsal. The whole point of deciding on Day 0 is to remove that conversation.

## Consequences

**Good**

- ~2 developer-days returned to the plan on Day 0. They fund the multi-currency work
  (ADR-0011), which the customer explicitly asked for and which is roughly the same size.
- No Qiskit — a large dependency tree, a slow build, and a container image several hundred MB
  bigger.
- One fewer thing that can fail live.
- **The reasoning is itself a deliverable.** Being able to explain why a technology does not
  fit is a more useful signal than a notebook that runs QAOA on four assets. We will present
  it that way.

**Bad, and accepted**

- We do not demonstrate any quantum computing. If it turns out to be explicitly assessed, this
  is a gap — see the trigger below.
- "Quantum" on a slide is memorable in a way "we thought about it and declined" is not. We
  accept that trade knowingly, and mitigate it by presenting the analysis rather than silently
  omitting the feature.
- Somebody on the team wanted to build it. That is a real cost and worth naming.

## Alternatives considered

| Alternative | Why not |
|---|---|
| **Build it as a Day 5 stretch** | Day 5 is the schedule buffer for Days 1–4 overflow (`/docs/RISKS.md` R2). Committing it in advance to a non-requirement removes the buffer |
| **Stub the endpoint, return a canned result** | Worse than not building it. A fake optimiser that looks real is a thing we would have to explain or, worse, not explain |
| **Classical mean-variance optimiser instead** | Genuinely useful, ~4 hours with a small QP library, and it produces a real efficient frontier. **This is the right feature** — but it still is not on the customer's priority list, so it belongs in Day 7, not Day 5. Recorded here as the successor |
| **Quantum-inspired annealing (simulated annealing on the QUBO)** | Cheap and honest, but "quantum-inspired" is marketing for "we ran simulated annealing", and we would have to say so |

## Revisit when

- **The instructor states that quantum is assessed.** Then it goes back in, at ~2 days, and
  something of equal size comes out — most likely GraphQL and the insights service together.
  This is the one trigger that changes the decision, and it should be checked with the
  customer early rather than assumed.
- The product needs real optimisation. Build the **classical** mean-variance optimiser first;
  it is better on every axis at this scale.
- Quantum hardware becomes accessible with an asset count beyond classical brute force —
  hundreds of assets. Not this decade for this problem, and not this project.
