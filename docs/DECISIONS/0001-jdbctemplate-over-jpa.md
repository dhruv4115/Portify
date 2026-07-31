# ADR-0001 · `NamedParameterJdbcTemplate` and explicit SQL, not JPA

**Status:** Accepted · **Date:** Day 0 · **Owner:** Dev A · **Supersedes:** —

## Context

We are storing financial data — quantities to six decimal places, money to four, FX rates to
eight — and computing a portfolio valuation from it. The team is three developers with six
days. The default choice at this bank would be Spring Data JPA, so choosing otherwise needs
an argument.

Three properties of this particular problem push against JPA:

1. **The reads are analytical, not object-graph.** The most important query in the product
   pulls a portfolio's transactions, a date range of prices for several instruments, and a
   date range of FX rates, then folds them into a time series. That is three flat result sets,
   not an aggregate root with lazy associations. Expressed through JPA it becomes either
   three repository calls that fight the entity manager, or a native query — at which point
   we are writing SQL anyway, with an ORM in the way.
2. **`DECIMAL` precision must survive the round trip, visibly.** With explicit SQL and a
   hand-written `RowMapper`, the line that reads `rs.getBigDecimal("close_price")` is right
   there in the file. With JPA, precision depends on the column definition, the dialect and
   the provider's type descriptor, none of which are visible at the call site.
3. **N+1 is a correctness risk here, not just a performance one.** A lazily-loaded
   association inside a valuation loop produces a number that is *right* but takes 4 seconds.
   Under demo conditions that reads as a broken product.

The counter-argument is real: JPA writes the boilerplate for us, and we are hand-writing
`RowMapper`s and insert statements instead.

## Decision

**Use `NamedParameterJdbcTemplate` with explicit SQL and hand-written `RowMapper`s. No JPA,
no Hibernate, no Spring Data — not for any entity, not "just this once".**

Supporting rules:

- One `BaseRepository` owns the `KeyHolder` + `RETURN_GENERATED_KEYS` insert helper, written
  once. Nobody else constructs a `KeyHolder`.
- `RowMapper`s are hand-written and named `*RowMapper`. No `BeanPropertyRowMapper` — it
  reflects, it fails silently on a renamed column, and it is exactly the magic we are avoiding.
- No `JdbcTemplate` outside a `*Repository`. Enforced by `ArchitectureTest`.
- Named parameters only. No string concatenation of user input, ever.

## Consequences

**Good**

- Every query is visible, greppable and reviewable. `git grep "FROM txn"` finds every read.
- No lazy-loading exceptions, no N+1 surprises, no detached-entity confusion, no first-level cache making a test pass that would fail in production.
- `DECIMAL` handling is explicit at the point of use.
- The `WHERE user_id = :userId` predicate is visible in the SQL rather than implied by a
  specification or a filter annotation. For a multi-user system whose main security property
  is per-user scoping, this is the strongest argument of all — the security control and the
  code that reviewers read are the same text.
- Testcontainers integration tests exercise the actual SQL that ships.

**Bad, and accepted**

- More code. Roughly 30–40 extra lines per aggregate for the mapper and the insert.
- Column renames are caught at runtime by a test, not at compile time by an entity.
- No free dirty-checking, cascading or optimistic locking — we hand-roll the one lock we need
  (`SELECT … FOR UPDATE` on `portfolio`, see ADR-0002).
- Anyone joining the team who reaches for `@Entity` out of habit has to be redirected. Hence
  the rule in `CLAUDE.md` rather than just this ADR.

**Neutral**

- Roughly a day of extra typing across the week, against a day saved not debugging Hibernate
  behaviour under a deadline. In our judgement this nets to zero on cost and positive on
  confidence.

## Alternatives considered

| Alternative | Why not |
|---|---|
| **Spring Data JPA** | Less code, and everyone knows it. But it hides the `user_id` predicate, makes precision implicit, and turns the performance query into a native query anyway. The saving is real; the loss of visibility on our two riskiest properties is worse |
| **jOOQ** | Genuinely attractive — type-safe SQL, still explicit. Rejected on cost: code generation in the build, another plugin to configure, a licence question for the commercial dialects, and a day of setup we do not have |
| **MyBatis** | Externalised SQL in XML. Splits the query from the code that uses it, for no benefit at this size |
| **JPA for writes, JdbcTemplate for reads** | Two persistence models in one codebase, with three developers and six days. The worst of both |

## Revisit when

- The team exceeds ~6 developers and the boilerplate becomes the bottleneck rather than the clarity benefit.
- A second bounded context appears whose access pattern is genuinely object-graph-shaped.
- Someone proposes jOOQ **with** a day to spare for the build setup. That would be a better answer than either of today's options.
