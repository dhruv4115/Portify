package com.protify.portfolio.api.user;

import java.time.Instant;

/**
 * Stand-in for Dev B's {@code AppUser} (platform/user, D1-B2), shaped after
 * REFERENCE_DESIGN.md §1's {@code app_user} columns minus {@code googleSub}/{@code updatedAt}
 * (the {@code sub} claim never needs to reach this layer; API_CONTRACT.md §1 doesn't expose
 * either field).
 *
 * <p>Temporary seam, agreed with the team as the Day 1 way to unblock {@code MeController}
 * while {@code portfolio-platform/security} doesn't exist yet: Dev C codes the controller and
 * mapper against this shape today, Dev B's real {@link CurrentUserResolver} implementation
 * replaces the one in this package once their JIT-provisioning lands.
 */
public record CurrentUser(
        Long id,
        String email,
        String displayName,
        String pictureUrl,
        Instant createdAt) {
}
