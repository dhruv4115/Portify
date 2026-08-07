# DEV_SETUP.md — local environment (Day 0)

Steps to get `mvn clean verify` green and a native local MySQL 8.4 reachable, macOS +
Homebrew. Followed and verified working end-to-end on this machine 2026-07-31.

## 1. Prerequisites

- Java 21 (`java -version`)
- Maven (or use the bundled `./mvnw`)
- Docker Desktop or a Docker daemon on the PATH — the `*IT` tests run against a real MySQL
  8.4 via Testcontainers, not a mock. `docker info` must succeed before `mvn clean verify`.
- Homebrew (`brew --version`)

## 2. Install MySQL 8.4 natively

The app itself (once `PortfolioApplication` exists) and any manual/local testing need a
native MySQL, separate from the Testcontainers instance the IT suite spins up on its own.

```bash
brew install mysql@8.4
brew services start mysql@8.4
```

Verify the server is actually 8.4+ — `CHECK` constraints in the baseline schema need
8.0.16+, and we're targeting 8.4 specifically:

```bash
/usr/local/opt/mysql@8.4/bin/mysql -u root -e "SELECT VERSION();"
# → 8.4.x
```

If `mysql` isn't on your PATH, prefix commands with `/usr/local/opt/mysql@8.4/bin/` or add
that directory to your shell profile.

## 3. Create the schemas and the dedicated user

```sql
CREATE DATABASE portfolio      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE portfolio_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'protify'@'localhost' IDENTIFIED BY '<local-only-password>';
GRANT ALL ON portfolio.* TO 'protify'@'localhost';
GRANT ALL ON portfolio_test.* TO 'protify'@'localhost';
FLUSH PRIVILEGES;
```

Run that as `mysql -u root`. Pick your own local password — do not reuse one from
another system, and never commit it.

## 4. `serverTimezone=UTC` — do not skip this

Every JDBC URL against this database **must** carry `?serverTimezone=UTC`:

```
jdbc:mysql://localhost:3306/portfolio?serverTimezone=UTC
```

Without it, `DATETIME` values shift silently between the JVM and MySQL and date
assertions become flaky in a way that looks like a logic bug, not a config bug. This is
`/docs/RISKS.md` R13 — it has cost half a day on past projects. The Testcontainers base
class (`AbstractIntegrationTest`) already bakes this in via `.withUrlParam("serverTimezone",
"UTC")`; do the same for your own local `.env`.

Proof this is wired correctly — connect as the app user and confirm the round trip:

```bash
mysql -u protify -p -h 127.0.0.1 portfolio -e "SELECT VERSION();"
```

## 5. Local environment file

Copy `.env.example` to `.env` (git-ignored, never commit it) and fill in the password you
chose in step 3:

```
DB_URL=jdbc:mysql://localhost:3306/portfolio?serverTimezone=UTC
DB_URL_TEST=jdbc:mysql://localhost:3306/portfolio_test?serverTimezone=UTC
DB_USERNAME=protify
DB_PASSWORD=<your local password>
```

## 6. Build

```bash
./mvnw clean verify              # full build — starts a Testcontainers MySQL 8.4, runs Flyway, runs *IT
./mvnw clean verify -DskipITs    # fast loop, no Docker needed
```

Both must be green before you open a PR (`/docs/DEFINITION_OF_DONE.md`).

## Troubleshooting

- **`docker info` hangs or errors** — start Docker Desktop first; Testcontainers needs a
  running daemon, not just the CLI installed.
- **Flyway warns "MySQL 8.4 is newer than this version of Flyway and support has not been
  tested"** — expected and harmless; migrations still apply and validate correctly.
- **Port 3306 already in use** — another MySQL (e.g. `mysql@8.0` from a prior project) is
  probably already bound. `brew services list` shows what's running; stop the other one or
  point `DB_URL` at whichever instance is actually 8.4.
- **Testcontainers "Reuse was requested but the environment does not support the reuse of
  containers"** — harmless warning; each IT class still gets a working container, it just
  isn't reused across `mvn` invocations unless you set
  `testcontainers.reuse.enable=true` in `~/.testcontainers.properties`.
