# ADR-0007 · Flyway version numbers partitioned per developer

**Status:** Accepted. Note: [ADR-0012](0012-single-module.md) (Day 1) removed the separate
`portfolio-db` Maven module referenced below — migrations now live in
`src/main/resources/db/migration/` inside the one module. The decision recorded here (version
ranges partitioned by developer, migrations in one add-only directory) is otherwise unaffected
· **Date:** Day 0 · **Owner:** Dev A · **Supersedes:** —

## Context

Three developers, one database, six days, and everyone will need schema changes. The failure
mode is well known and reliably arrives around Day 3: two developers both create `V5__…sql`
on their own branches, both build fine locally, and the collision only surfaces when the
second branch merges. Flyway then either refuses to start on a duplicate version or — worse,
if one migration was already applied somewhere — silently skips one.

The related failure is a developer editing a migration that has already run on someone else's
machine. Flyway checksums applied migrations; changing one produces a validation error that
looks like a corrupt database and is usually "fixed" by dropping the schema, which loses
everyone's local data.

A third, quieter problem: if migrations live in `portfolio-api`, then `portfolio-core` — which
`api` depends on, not the other way round — cannot see them, and its Testcontainers tests have
no schema to run against.

## Decision

**Version numbers are partitioned by developer, and migrations live in their own module.**

| Developer | Range | Typical use |
|---|---|---|
| **Dev A** | `V1` – `V9` | baseline schema, instruments seed, indexes, demo data |
| **Dev B** | `V10` – `V19` | `fx_rate`, seeded prices and rates, market-data support |
| **Dev C** | `V20` – `V29` | API-driven schema needs, GraphQL support |
| Reserved | `V30`+ | post-sprint, allocated at the time |

Supporting rules:

1. **Migrations live in `portfolio-db`**, a module containing nothing else. `core`, `platform`
   and `api` all depend on it, so every module's integration tests run against the real schema.
2. **`portfolio-db` is the one shared directory.** Everyone adds files; nobody edits anyone
   else's. Since a merge only ever adds new filenames, a conflict there is impossible by
   construction.
3. **Never edit an applied migration.** Correct a mistake with a new one. If it was applied
   only on your own machine and nowhere else, `flyway clean` locally is fine — but the moment
   it is pushed, it is immutable.
4. `FlywayMigrationIT` asserts on every build that all migrations apply to an **empty** schema
   and that no version number is duplicated. The rule is enforced by the build, not by memory.
5. Naming: `V<n>__<snake_case_description>.sql`, double underscore, descriptive.

## Consequences

**Good**

- Version collisions are structurally impossible while everyone stays in range.
- Migration merge conflicts are impossible — merges only add files.
- The owner of any schema change is readable from the filename. `V12` is Dev B's, immediately.
- `portfolio-db` as a separate module solves the test-visibility problem: `core`'s repository
  integration tests get the real schema without `core` depending on `api`.
- New developers get an unambiguous rule instead of a convention they have to infer.

**Bad, and accepted**

- **Version numbers are not chronological.** `V20` may be applied before `V11` in wall-clock
  terms. Flyway applies in version order, which is what matters, but reading the directory
  does not tell you the order things were built. Acceptable — `git log` does.
- Nine slots per developer. If Dev A needs a tenth migration in six days, something has gone
  wrong upstream and a conversation is the right response.
- A migration that genuinely needs two developers' changes has to be split, or one developer
  writes it in their range on the other's behalf. This has come up once in planning
  (`V10__fx_rate.sql` — Dev B's range, though `fx_rate` is referenced by Dev A's valuation
  code) and the rule handled it cleanly: whoever *owns the module* owns the migration.
- Timestamp-based versioning (`V20260731120000__…`) would also prevent collisions and would be
  chronological. Rejected below.

## Alternatives considered

| Alternative | Why not |
|---|---|
| **Timestamp versions** (`V20260731143000__…`) | Genuinely good, and standard on larger teams — collisions are essentially impossible and order is chronological. Rejected here because the filenames are unreadable at a glance, ordering across branches still surprises people, and with three developers for six days the ranges are simpler and carry ownership information the timestamps do not |
| **One shared migration file that everyone edits** | Conflicts on every merge. This is the thing being avoided |
| **Ask in the channel before claiming a number** | Works until someone forgets, which on Day 4 at 17:20 is guaranteed. Coordination that relies on remembering is not a control |
| **Migrations inside each functional module** | Flyway supports multiple locations, so it would work. Rejected because migration ordering then depends on classpath scanning across modules, which is harder to reason about than one ordered directory |
| **Hibernate `ddl-auto`** | We have no Hibernate (ADR-0001), and generated schema is not reviewable |

## Revisit when

- The team grows past ~5 developers, or ranges run out. Timestamp versioning becomes the right
  answer at that point, and the migration path is simply to start numbering `V2026…` from a
  stated date.
- The project moves past its first release and migrations start needing a rollback story —
  Flyway Undo, or forward-only with explicit compensating migrations. Forward-only is the
  better default and is what we do implicitly today.
