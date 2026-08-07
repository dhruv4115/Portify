package com.protify.portfolio.api.user;

/**
 * The agreed seam for Dev B's {@code CurrentUserResolver} (day-1-dev-B.md D1-B2): reads the
 * validated {@code Jwt}'s {@code sub} claim, looks up (or JIT-provisions) the {@code app_user}
 * row, and exposes the result as a request-scoped bean. {@code MeController} depends on this
 * interface, not on Dev B's package, so it compiles and is unit-testable today; Dev B supplies
 * the real {@code @Component} (request-scoped) implementation when {@code security}/{@code
 * user} land.
 */
public interface CurrentUserResolver {

    CurrentUser resolve();
}
