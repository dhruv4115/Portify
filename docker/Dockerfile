# D4-B1 — multi-stage image. Single Maven module (ADR-0012) — no portfolio-*/pom.xml globbing,
# unlike the pre-Day-1 five-module layout the original template assumed.
#
# ---- build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Dependency layer cached separately from source so an ordinary code change doesn't
# re-download the internet — a 4-minute rebuild becomes ~40s (D4-B1).
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# ConfigurationSmokeTest resolves these two against the working directory (Path.of(".env.example"),
# Path.of(".gitignore")) rather than the classpath, and Surefire runs it in this stage — without
# them the image build fails on tests that pass on the CI runner, where CWD is the full checkout.
# Copying the real files, not stubs, so the secret-scan and .env-is-ignored assertions still mean
# something here. Keep them out of any future .dockerignore.
COPY .gitignore .env.example ./
# -DskipITs, not -DskipTests: unit/slice tests (Surefire) still run and must pass to get an
# image; Testcontainers integration tests (Failsafe) need a real Docker-reachable MySQL, which
# does not exist during an image build, and belong in the pipeline instead (ADR/RISKS.md R7).
RUN mvn -B -DskipITs clean package

# ---- run ----
FROM eclipse-temurin:21-jre-alpine

# Non-root user — at a bank this gets noticed, and it is the difference between a container
# escape being a shell and a shell as root.
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
USER app

# Without this the container's zone differs from the developer's and DATETIME-based date logic
# drifts silently (/docs/RISKS.md R13) — the same reason the JDBC URL carries serverTimezone=UTC.
ENV JAVA_TOOL_OPTIONS="-Duser.timezone=UTC"

EXPOSE 8080

# /actuator/health is permitAll (SecurityConfig) — no credentials needed for the probe itself.
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
