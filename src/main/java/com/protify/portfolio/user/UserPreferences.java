package com.protify.portfolio.user;

/**
 * A user's display choices, as stored. Every field is nullable and {@code null} is meaningful:
 * it means this account has never expressed a preference, so the device's own default stands.
 *
 * <p>That distinction is the whole reason this type does not carry defaults of its own. If it
 * substituted {@code "system"} for a {@code null} theme, the server would be asserting a
 * preference the user never made, and the client — which cannot tell an asserted default from a
 * chosen one — would overwrite whatever the browser had stored with it.
 */
public record UserPreferences(String theme, String language, String density, String motion) {

    public static UserPreferences none() {
        return new UserPreferences(null, null, null, null);
    }

    /** True when the user has never set anything at all — the server has no opinion to send. */
    public boolean isEmpty() {
        return theme == null && language == null && density == null && motion == null;
    }
}
