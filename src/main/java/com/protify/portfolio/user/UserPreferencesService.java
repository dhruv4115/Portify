package com.protify.portfolio.user;

import org.springframework.stereotype.Service;

/**
 * Reads and partially updates a user's stored display preferences.
 *
 * <p>Thin by design — the interesting decision (a {@code null} field means "leave it alone",
 * not "clear it") lives in the {@code COALESCE} in
 * {@link AppUserRepository#updatePreferences}, where it is one statement and cannot race
 * against a concurrent save from another device. This class exists so no controller reaches a
 * repository directly, matching every other read path in the codebase.
 */
@Service
public class UserPreferencesService {

    private final AppUserRepository appUserRepository;

    public UserPreferencesService(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    public UserPreferences get(long userId) {
        return appUserRepository.findPreferences(userId);
    }

    /** @return the stored state after the update, which is what the caller echoes back */
    public UserPreferences update(long userId, UserPreferences changes) {
        return appUserRepository.updatePreferences(userId, changes);
    }
}
