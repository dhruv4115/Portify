package com.protify.portfolio.api.dto;

import java.time.Instant;

/**
 * API_CONTRACT.md §1. {@code displayName} and {@code pictureUrl} are nullable — not every
 * Google account exposes a {@code name}/{@code picture} claim.
 *
 * <p>{@code preferences} rides along on this response, rather than only being available from
 * {@code GET /me/preferences}, because the client already fetches {@code /me} during boot. A
 * second round trip before first paint is exactly the delay that makes the page flash the
 * wrong theme — the thing storing preferences server-side was meant to fix. Its fields are
 * individually null when the account has never saved that one, meaning "keep whatever this
 * device already uses".
 */
public record UserResponse(
        Long id,
        String email,
        String displayName,
        String pictureUrl,
        Instant createdAt,
        PreferencesDto preferences) {
}
