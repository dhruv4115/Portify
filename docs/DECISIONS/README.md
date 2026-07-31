# Architecture Decision Records

One file per decision. Format: Context → Decision → Consequences → Alternatives → Revisit when.

An ADR records **why**, at the moment it was decided, by someone who did not yet know how it
turned out. Do not edit a decided ADR to match what happened — supersede it with a new one
and link them. The record of a decision that turned out wrong is worth more than a tidy file.

| # | Decision | Status | Day | Owner |
|---|---|---|---|---|
| [0001](0001-jdbctemplate-over-jpa.md) | `NamedParameterJdbcTemplate` and explicit SQL, not JPA | Accepted | 0 | Dev A |
| [0002](0002-transaction-centric-model.md) | Transactions are the source of truth; holdings are a projection | Accepted | 0 | Dev A |
| [0003](0003-modular-monolith.md) | Modular monolith plus one Python service, not microservices | Accepted | 0 | Tech lead |
| [0004](0004-google-id-token-as-bearer.md) | Google ID token used directly as the bearer token | Accepted | 0 | Dev B |
| [0005](0005-weighted-average-cost.md) | Weighted-average cost basis, not FIFO lots | Accepted | 0 | Dev A |
| [0006](0006-seeded-price-history.md) | Two years of real prices seeded in a migration | Accepted | 0 | Dev B |
| [0007](0007-flyway-number-ranges.md) | Flyway version ranges partitioned per developer | Accepted | 0 | Dev A |
| [0008](0008-market-data-provider-port.md) | Market data and FX behind ports; no provider in the domain | Accepted | 0 | Dev B |
| [0009](0009-quantum-cut.md) | The quantum optimiser is cut, not deferred | Accepted | 0 | Tech lead |
| [0010](0010-performance-series-in-java.md) | The performance series is an in-memory fold, not SQL | Accepted | 0 | Dev A |
| [0011](0011-multi-currency-valuation.md) | Native currency stored, base currency presented | Accepted | 0 | Tech lead |
| [0012](0012-single-module.md) | Collapse the five Maven modules into one | Accepted | 1 | Dev A |

The first eleven are dated Day 0 because they are the decisions that had to be settled before
code existed. `0012` onward are decided later and dated accordingly — `0012` itself partially
supersedes `0003`; both are linked from each other.
