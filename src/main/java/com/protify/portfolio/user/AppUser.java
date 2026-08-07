package com.protify.portfolio.user;

import java.time.Instant;

/**
 * Persistence-shaped representation of {@code app_user} (see
 * {@code /docs/REFERENCE_DESIGN.md} §1). Never returned directly from a controller — Dev C's
 * {@code com.protify.portfolio.api.user.CurrentUser} plus {@code UserMapper}/{@code UserResponse}
 * handle that boundary; {@link CurrentUserResolverImpl} is the seam between the two shapes.
 */
public record AppUser(
        Long id,
        String googleSub,
        String email,
        String displayName,
        String pictureUrl,
        Instant createdAt,
        Instant updatedAt) {
}
