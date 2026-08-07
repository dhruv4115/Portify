package com.protify.portfolio.user;

import com.protify.portfolio.api.user.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D1-B2 — {@code CurrentUserResolverTest}: unit test, mocked {@link AppUserRepository}. No
 * Spring context, no database.
 */
@ExtendWith(MockitoExtension.class)
class CurrentUserResolverTest {

    @Mock
    private AppUserRepository appUserRepository;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void firstSignInCreatesExactlyOneRow() {
        CurrentUserResolverImpl resolver = new CurrentUserResolverImpl(appUserRepository);
        authenticateAs("sub-1", "new@example.com");
        AppUser created = appUser(1L, "sub-1", "new@example.com");

        when(appUserRepository.findByGoogleSub("sub-1")).thenReturn(Optional.empty());
        when(appUserRepository.insert("sub-1", "new@example.com", "Test User", "https://example.com/pic.jpg"))
                .thenReturn(created);

        CurrentUser resolved = resolver.resolve();

        assertThat(resolved.id()).isEqualTo(1L);
        verify(appUserRepository, times(1))
                .insert("sub-1", "new@example.com", "Test User", "https://example.com/pic.jpg");
    }

    @Test
    void secondSignInCreatesNoRow() {
        CurrentUserResolverImpl resolver = new CurrentUserResolverImpl(appUserRepository);
        authenticateAs("sub-2", "existing@example.com");
        AppUser existing = appUser(2L, "sub-2", "existing@example.com");

        when(appUserRepository.findByGoogleSub("sub-2")).thenReturn(Optional.of(existing));

        CurrentUser resolved = resolver.resolve();

        assertThat(resolved.id()).isEqualTo(2L);
        assertThat(resolved.email()).isEqualTo("existing@example.com");
        verify(appUserRepository, never()).insert(anyString(), anyString(), any(), any());
    }

    @Test
    void concurrentFirstSignInLosesTheRaceAndReReadsTheWinnersRow() {
        CurrentUserResolverImpl resolver = new CurrentUserResolverImpl(appUserRepository);
        authenticateAs("sub-3", "race@example.com");
        AppUser winnersRow = appUser(3L, "sub-3", "race@example.com");

        when(appUserRepository.findByGoogleSub("sub-3"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winnersRow));
        when(appUserRepository.insert("sub-3", "race@example.com", "Test User", "https://example.com/pic.jpg"))
                .thenThrow(new DuplicateKeyException("uk_app_user_google_sub"));

        CurrentUser resolved = resolver.resolve();

        assertThat(resolved.id()).isEqualTo(3L);
        verify(appUserRepository, times(1))
                .insert("sub-3", "race@example.com", "Test User", "https://example.com/pic.jpg");
    }

    @Test
    void throwsWhenCalledOutsideAnAuthenticatedRequest() {
        CurrentUserResolverImpl resolver = new CurrentUserResolverImpl(appUserRepository);
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("anonymous", null));

        assertThatIllegalStateException().isThrownBy(resolver::resolve);
    }

    private static AppUser appUser(Long id, String googleSub, String email) {
        return new AppUser(id, googleSub, email, "Test User", "https://example.com/pic.jpg",
                Instant.now(), Instant.now());
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
