# Day 4 — Dev B — Docker, compose, and a real Jenkins pipeline

## You have no prior context. Read this whole file before doing anything.

You are **Dev B** on a three-person team building **Protify**, a Portfolio Management REST API
(Java 21, Spring Boot 3.5.16, MySQL 8.4, `NamedParameterJdbcTemplate` — **no JPA**) plus a
React frontend. Base package `com.protify.portfolio`.

**You own:** `portfolio-platform`, `scripts/`, `docker/`, `Jenkinsfile`, Flyway `V10`–`V19`.

**Status:** the MVP shipped on Day 3, tagged `v0.1-mvp`. Google auth, real prices, real FX,
three currencies, a working chart. Your caching, rate limiting and circuit breaking are in
place and reads survive the network being off.

**Today the product becomes deployable and the pipeline becomes real.**

**Read first:** `/docs/PLAN.md` §2.4 · `/docs/RISKS.md` R7, R8 · `/docs/TEST_PLAN.md` §6, §8.

> **Do all of this in the Linux VM (or Docker Desktop if it is installed).** You proved on
> Day 0 which machines have a working daemon.

---

## D4-B1 · Multi-stage Dockerfile — 2.0 h · 🔴

`docker/Dockerfile`

```dockerfile
# ---- build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY portfolio-*/pom.xml ./
RUN mvn -B -q dependency:go-offline        # cache the dependency layer
COPY . .
RUN mvn -B -DskipITs clean package

# ---- run ----
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /app/portfolio-api/target/*.jar app.jar
USER app
ENV JAVA_TOOL_OPTIONS="-Duser.timezone=UTC"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java","-jar","/app/app.jar"]
```

Three things that are easy to skip and expensive to skip:

- **Non-root user.** At a bank this gets noticed.
- **`-Duser.timezone=UTC`.** Without it the container's zone differs from the developer's and date logic drifts — `/docs/RISKS.md` R13.
- **Copy the POMs before the sources** so a code change does not re-download every dependency. It turns a 4-minute rebuild into 40 seconds, which you will feel today.

`-DskipITs` in the image build is correct: integration tests belong in the pipeline, not in the
image build, where there is no database.

**Acceptance:** image under 400 MB; container boots; `/actuator/health` returns 200 UP.

---

## D4-B2 · `compose.yml` — 1.5 h · 🔴

`docker/compose.yml` — MySQL 8.4 plus the API.

- MySQL with a named volume, `utf8mb4`, and `--default-time-zone=+00:00`.
- A **healthcheck on MySQL** and `depends_on: condition: service_healthy` for the API. Without it, the API starts before MySQL is accepting connections, Flyway fails, and the container restart-loops. This is the single most common compose mistake.
- All secrets from `.env`, which is git-ignored. `.env.example` is tracked with placeholders.
- Flyway runs on API boot — the whole schema plus two years of seeded prices and FX from `V11`/`V12`.

**Acceptance:** `docker compose down -v && docker compose up --build` from a **clean volume**
produces a working API with seeded data. Test it exactly that way — a compose file that only
works against an existing volume is not a deliverable.

---

## D4-B3 · `Jenkinsfile` — 2.5 h · 🔴 **the deliverable**

`/Jenkinsfile`, declarative, at the repository root.

```groovy
pipeline {
  agent any
  options { timestamps(); buildDiscarder(logRotator(numToKeepStr: '20')) }
  stages {
    stage('Checkout')     { steps { checkout scm } }
    stage('Build')        { steps { sh 'mvn -B clean compile' } }
    stage('Unit tests')   { steps { sh 'mvn -B test' }
                            post { always { junit '**/target/surefire-reports/*.xml' } } }
    stage('Integration')  { steps { sh 'mvn -B verify -DskipUTs' }
                            post { always { junit '**/target/failsafe-reports/*.xml' } } }
    stage('Coverage')     { steps { jacoco() } }
    stage('Package')      { steps { sh 'mvn -B -DskipTests package' } }
    stage('Docker build') { steps { sh 'docker build -f docker/Dockerfile -t protify:${BUILD_NUMBER} .' } }
    stage('Archive')      { steps { archiveArtifacts 'portfolio-api/target/*.jar' } }
  }
  post {
    failure { echo 'Pipeline failed' }
    always  { cleanWs() }
  }
}
```

**Use only ordinary Maven and Docker commands.** No Jenkins-only plugin steps beyond `junit`,
`jacoco` and `archiveArtifacts` — that way the same stages run locally and in GitHub Actions,
which is `/docs/RISKS.md` R8's mitigation.

The Integration stage runs Testcontainers, so the Jenkins agent needs a Docker daemon and the
Jenkins user must be in the `docker` group. Sort that out today.

**Acceptance: a real `git push` triggers a real green run on the VM.** Not a manually-started
build — a push-triggered one, because that is the CI/CD flow the customer described.

**Take a screenshot of the green run today.** The VM is a single point of failure and Day 6 is
too late to discover it is down (`/docs/RISKS.md` R8).

---

## D4-B4 · Mirror it in GitHub Actions — 0.5 h

Update `.github/workflows/ci.yml` so both CIs run the same stages. Both must be green. GitHub
Actions is the insurance policy for the Jenkins VM being unreachable.

---

## Rules

- **No secret in any image, compose file or `Jenkinsfile`.** Everything from env or Jenkins credentials.
- **You may not edit `pom.xml`.** Ask Dev A.
- `portfolio-common` is frozen. Migrations only in `V10`–`V19`.
- No `double`, no `float`. No JPA. No `JdbcTemplate` outside a `*Repository`.
- Do not weaken a test to make the pipeline green. If the pipeline finds a real failure, that is the pipeline working.

## Do not touch

`portfolio-core/**` (Dev A) · `portfolio-api/**` (Dev C) · `portfolio-common/**` ·
any POM · migrations `V1`–`V9`, `V20`+.

---

## Done when

- [ ] Multi-stage image builds, under 400 MB, non-root, UTC
- [ ] `docker compose down -v && docker compose up --build` gives a working API **from a clean volume**
- [ ] MySQL healthcheck gates API startup — no restart loop
- [ ] `Jenkinsfile` at the repo root, declarative, stages visible
- [ ] **A real push produced a real green Jenkins run — screenshot taken**
- [ ] Testcontainers integration tests run inside the pipeline
- [ ] JaCoCo report published in Jenkins
- [ ] GitHub Actions mirrors the same stages and is green
- [ ] No secret in any artefact
- [ ] Merged to `develop` by 17:30

## Hand-off

Post the Jenkins build URL and the screenshot in the channel.

**Tonight's demo is yours:** `docker compose up` from nothing, the app working, and the green
Jenkins run that built it.

Tomorrow (Day 5) is the buffer. If everything above is green you build the **Python FastAPI
insights service** with a canned deterministic fallback for when there is no LLM key and no
network — the fallback is the part that must work, and `engine: RULE_BASED` in the response
makes it visibly honest rather than disguised. **If anything from Days 1–4 is unfinished,
tomorrow is for that instead.**
