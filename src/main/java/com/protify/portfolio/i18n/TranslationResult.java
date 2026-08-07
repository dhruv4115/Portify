package com.protify.portfolio.i18n;

import java.util.List;
import java.util.Map;

/**
 * @param entries         every requested key, translated where possible and holding its English
 *                        source where not — never a partial map, so a caller cannot accidentally
 *                        render a missing key
 * @param untranslatedKeys the subset of {@code entries} whose values are still English
 * @param anyTranslated   false when nothing was translated at all, which is what distinguishes
 *                        an honest {@code PASSTHROUGH} response from a partially successful one
 */
public record TranslationResult(Map<String, String> entries, List<String> untranslatedKeys, boolean anyTranslated) {
}
