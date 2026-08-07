package com.protify.portfolio.api.mapper;

import com.protify.portfolio.api.dto.PreferencesDto;
import com.protify.portfolio.api.dto.UserResponse;
import com.protify.portfolio.api.user.CurrentUser;
import com.protify.portfolio.user.UserPreferences;
import org.springframework.stereotype.Component;

/**
 * Explicit, hand-written mapping — no MapStruct, no reflection (day-1-dev-C.md D1-C3).
 * {@link CurrentUser} is Dev C's temporary seam standing in for Dev B's {@code AppUser}; this
 * mapper (not {@code MeController}) is what changes the day that type is replaced with the
 * real persistence entity.
 */
@Component
public class UserMapper {

    /** The identity half of {@code /me}, for callers with no preferences to attach. */
    public UserResponse toResponse(CurrentUser currentUser) {
        return toResponse(currentUser, UserPreferences.none());
    }

    public UserResponse toResponse(CurrentUser currentUser, UserPreferences preferences) {
        return new UserResponse(
                currentUser.id(),
                currentUser.email(),
                currentUser.displayName(),
                currentUser.pictureUrl(),
                currentUser.createdAt(),
                toDto(preferences));
    }

    /**
     * Always an object, never {@code null} — including for an account that has saved nothing,
     * which comes back as four null fields.
     *
     * <p>Collapsing that case to a null container was tempting and would have said the same
     * thing twice: a per-field {@code null} already means "no server-side preference", and a
     * client must handle those anyway, since someone can set a theme without setting a
     * language. Two shapes for one state would only give {@code GET /me/preferences} an empty
     * body to explain.
     */
    public PreferencesDto toDto(UserPreferences preferences) {
        UserPreferences source = preferences == null ? UserPreferences.none() : preferences;
        return new PreferencesDto(
                source.theme(),
                source.language(),
                source.density(),
                source.motion());
    }
}
