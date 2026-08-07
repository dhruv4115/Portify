package com.protify.portfolio.fx;

import java.math.BigDecimal;
import java.util.Map;

/** Shape of {@code https://api.frankfurter.app/{date}?from=..&to=..} — only the fields this
 * adapter reads. {@code date} is the ECB business date the rate actually applies to, which can
 * differ from the requested date on a weekend/holiday — Frankfurter resolves that itself. */
record FrankfurterResponse(BigDecimal amount, String base, String date, Map<String, BigDecimal> rates) {
}
