package com.protify.portfolio.api.dto;

/**
 * A user's display preferences as the client sees them. Every field is nullable, and a
 * {@code null} means "no server-side preference — keep whatever this device already uses".
 * The client must treat that as "leave me alone", not as "reset to default".
 *
 * <p>The values are lowercase tokens ({@code "dark"}, {@code "compact"}) rather than an
 * uppercase Java enum, unlike every other enum in this API. That is deliberate: these strings
 * are written verbatim onto {@code <html data-theme="…">} and matched by CSS attribute
 * selectors, so the client's stylesheet — not this server — owns the vocabulary. Modelling
 * them as enums here would buy nothing and add a casing translation on both sides of the wire
 * that could only ever go wrong.
 *
 * @param language a BCP-47 primary subtag such as {@code "en"} or {@code "hi"}. Not restricted
 *                 to the bundled catalogues, so a user who picks a language this build does not
 *                 ship keeps that choice when a later build adds it.
 */
public record PreferencesDto(String theme, String language, String density, String motion) {
}
