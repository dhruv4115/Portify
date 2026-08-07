package com.protify.portfolio.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.protify.portfolio.api.NoOpDataSourceTestConfig;
import com.protify.portfolio.api.PortfolioApplication;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * D1-B3: configuration and secrets.
 *
 * <p>Two independent guarantees, both Docker-free so they run on every {@code mvn verify}:
 *
 * <ol>
 *   <li>the application context boots to a healthy state using only the properties checked
 *       into {@code application.properties} — every env-backed property has a safe default, so
 *       a fresh clone with no {@code .env} and no real environment variables still starts;
 *   <li>none of the tracked configuration files contain anything that reads like a live secret.
 *       CI's {@code .github/scripts/secret-scan.sh} covers the historical diff on every push;
 *       this test is the fast, IDE-runnable safety net for the specific files Dev B owns, and it
 *       catches a leak on this developer's machine before it's even committed.
 * </ol>
 */
@SpringBootTest(
        classes = PortfolioApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
                // Same reasoning as ApplicationContextLoadsTest: the stub DataSource is never
                // connected to, so the DB health indicator must stay off.
                "management.health.db.enabled=false"
        })
// This test lives outside com.protify.portfolio.api (PortfolioApplication's package), so
// @SpringBootTest can't auto-discover the @SpringBootConfiguration by searching upwards —
// classes=PortfolioApplication.class above fixes that, but it also disables Spring's automatic
// nested-@TestConfiguration detection, hence the explicit @Import here (same gotcha documented
// for SecurityConfigTest / UnauthorisedAccessIT).
@Import(NoOpDataSourceTestConfig.class)
class ConfigurationSmokeTest {

    // Mirrors KEY_PREFIX_PATTERN in .github/scripts/secret-scan.sh, so a plain `mvn verify` on
    // any machine catches the same obvious cases as the CI diff-scan, without needing git/bash.
    private static final Pattern KEY_PREFIX_PATTERN = Pattern.compile(
            "AIza[0-9A-Za-z_-]{10,}|sk-[A-Za-z0-9]{10,}|-----BEGIN [A-Z ]*PRIVATE KEY-----");

    // Every one of these has a checked-in default in application.properties (`${VAR:default}`);
    // asserting on them proves the context resolved real values rather than failing to start,
    // regardless of whether this machine also happens to have a local .env.
    @Value("${marketdata.provider}")
    private String marketDataProvider;

    @Value("${fx.provider}")
    private String fxProvider;

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    @Test
    void contextLoadsWithOnlyCheckedInDefaults() {
        assertThat(marketDataProvider).isNotBlank();
        assertThat(fxProvider).isNotBlank();
        assertThat(issuerUri).isEqualTo("https://accounts.google.com");
    }

    @Test
    void trackedConfigFilesContainNoLiveLookingSecret() throws IOException {
        List<Path> filesToScan = List.of(
                Path.of(".env.example"),
                Path.of("src/main/resources/application.properties"));

        for (Path file : filesToScan) {
            assertThat(Files.exists(file)).as("%s should exist", file).isTrue();
            String content = Files.readString(file);
            assertThat(KEY_PREFIX_PATTERN.matcher(content).find())
                    .as("%s should contain no live-looking secret", file)
                    .isFalse();
        }
    }

    @Test
    void dotEnvIsGitIgnoredSoItCanNeverBeCommitted() throws IOException {
        String gitignore = Files.readString(Path.of(".gitignore"));
        assertThat(gitignore.lines().map(String::trim)).contains(".env");
    }
}
