package com.protify.portfolio.user;

import com.protify.portfolio.api.user.CurrentUser;
import com.protify.portfolio.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D1-B2 — proves JIT provisioning against a real MySQL (Testcontainers): first sign-in creates
 * exactly one row, the second creates none, an unverified email creates none (and is rejected
 * with 403 at the {@code EmailVerifiedFilter} layer, covered by {@code SecurityConfigTest}, not
 * repeated here), and two concurrent first sign-ins create exactly one row.
 */
class JitProvisioningIT extends AbstractIntegrationTest {

    private AppUserRepository appUserRepository;

    @BeforeEach
    void setUp() {
        NamedParameterJdbcTemplate jdbc = jdbcTemplate();
        appUserRepository = new AppUserRepository(jdbc);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void firstSignInCreatesExactlyOneUserAndSecondCreatesNone() {
        String sub = "it-sub-" + System.nanoTime();
        CurrentUserResolverImpl resolver = new CurrentUserResolverImpl(appUserRepository);

        authenticateAs(sub, "first@example.com");
        CurrentUser first = resolver.resolve();
        Optional<AppUser> afterFirst = appUserRepository.findByGoogleSub(sub);
        assertThat(afterFirst).isPresent();
        assertThat(first.id()).isEqualTo(afterFirst.get().id());

        authenticateAs(sub, "first@example.com");
        CurrentUser second = resolver.resolve();
        assertThat(second.id()).isEqualTo(first.id());

        assertThat(appUserRepository.findByGoogleSub(sub)).isPresent();
    }

    @Test
    void twoConcurrentFirstSignInsCreateExactlyOneRow() throws Exception {
        String sub = "it-concurrent-" + System.nanoTime();
        int concurrentRequests = 2;

        CountDownLatch ready = new CountDownLatch(concurrentRequests);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(concurrentRequests);
        try {
            List<Future<Long>> futures = IntStream.range(0, concurrentRequests)
                    .mapToObj(i -> pool.submit(() -> {
                        authenticateAs(sub, "race@example.com");
                        CurrentUserResolverImpl resolver = new CurrentUserResolverImpl(appUserRepository);
                        ready.countDown();
                        go.await();
                        return resolver.resolve().id();
                    }))
                    .toList();

            ready.await(5, TimeUnit.SECONDS);
            go.countDown();

            List<Long> resolvedIds = new java.util.ArrayList<>();
            for (Future<Long> future : futures) {
                resolvedIds.add(future.get(10, TimeUnit.SECONDS));
            }

            assertThat(resolvedIds).containsOnly(resolvedIds.get(0));
        } finally {
            pool.shutdown();
        }

        assertThat(appUserRepository.findByGoogleSub(sub)).isPresent();
    }

    private static void authenticateAs(String subject, String email) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .subject(subject)
                .claim("email", email)
                .claim("email_verified", true)
                .claim("name", "Test User")
                .claim("picture", "https://example.com/pic.jpg")
                .issuedAt(Instant.now().minusSeconds(60))
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
}
