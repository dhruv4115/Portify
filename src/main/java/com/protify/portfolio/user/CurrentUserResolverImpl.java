package com.protify.portfolio.user;

import com.protify.portfolio.api.user.CurrentUser;
import com.protify.portfolio.api.user.CurrentUserResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/**
 * D1-B2 — the real {@link CurrentUserResolver}: reads the validated {@link Jwt} from the
 * security context, looks up (or JIT-provisions on first sight) the {@code app_user} row, and
 * exposes it as {@link CurrentUser}. Request-scoped, per the interface's contract (the seam
 * {@code api.user.PlaceholderCurrentUserResolver} stood in for) — a fresh resolution per
 * request, safe to constructor-inject into singleton controllers via Spring's scoped proxy.
 *
 * <p>The Google {@code sub} never reaches a repository beyond {@link AppUserRepository}'s
 * lookup; everything downstream of this class uses the internal {@code userId}
 * ({@link CurrentUser#id()}), never {@code sub}.
 *
 * <p>Assumes {@code email_verified: false} has already been rejected upstream with 403 — see
 * {@code EmailVerifiedFilter}, which runs for every authenticated request before a controller
 * (and therefore before this resolver) is ever reached.
 */
@Component
@RequestScope
public class CurrentUserResolverImpl implements CurrentUserResolver {

    private static final Logger log = LoggerFactory.getLogger(CurrentUserResolverImpl.class);

    private final AppUserRepository appUserRepository;

    public CurrentUserResolverImpl(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    @Override
    public CurrentUser resolve() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuth)) {
            throw new IllegalStateException(
                    "CurrentUserResolver.resolve() called outside an authenticated request; every "
                            + "endpoint that injects it must sit behind SecurityConfig's \"authenticated\" rule");
        }

        AppUser appUser = resolveAppUser(jwtAuth.getToken());
        return new CurrentUser(appUser.id(), appUser.email(), appUser.displayName(),
                appUser.pictureUrl(), appUser.createdAt());
    }

    private AppUser resolveAppUser(Jwt jwt) {
        String googleSub = jwt.getSubject();
        return appUserRepository.findByGoogleSub(googleSub)
                .orElseGet(() -> provisionNewUser(jwt, googleSub));
    }

    private AppUser provisionNewUser(Jwt jwt, String googleSub) {
        String email = jwt.getClaimAsString("email");
        String displayName = jwt.getClaimAsString("name");
        String pictureUrl = jwt.getClaimAsString("picture");

        try {
            AppUser created = appUserRepository.insert(googleSub, email, displayName, pictureUrl);
            log.info("Provisioned new app_user {} on first sign-in", created.id());
            return created;
        } catch (DuplicateKeyException raceLost) {
            // Two simultaneous first-sign-in requests for the same new sub: exactly one insert
            // wins, the other re-reads the winner's row. Never a second row, never a failure.
            log.debug("Lost the concurrent first-sign-in race; re-reading the row the other request created");
            return appUserRepository.findByGoogleSub(googleSub)
                    .orElseThrow(() -> new IllegalStateException(
                            "app_user row missing immediately after a unique-constraint conflict on google_sub"));
        }
    }
}
