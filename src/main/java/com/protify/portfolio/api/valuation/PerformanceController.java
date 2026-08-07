package com.protify.portfolio.api.valuation;

import com.protify.portfolio.api.dto.PerformanceInterval;
import com.protify.portfolio.api.dto.PerformanceResponse;
import com.protify.portfolio.api.user.CurrentUserResolver;
import com.protify.portfolio.common.enums.CurrencyCode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The customer's "view performance" (day-3-dev-C.md D3-C3), API_CONTRACT.md §12 — customer
 * priority 2, and PLAN.md §9 marks the chart behind it as the one thing never to cut.
 *
 * <p>Validate, delegate, map. The defaults are the contract's: a year back to today, daily. They
 * are resolved in <b>UTC</b> (CLAUDE.md non-negotiable #9), so the window a client gets does not
 * depend on the server's local zone.
 */
@RestController
@RequestMapping("/portfolios/{portfolioId}/performance")
public class PerformanceController {

    private static final long DEFAULT_WINDOW_YEARS = 1;

    private final PerformanceViewService performanceViewService;
    private final CurrentUserResolver currentUserResolver;

    public PerformanceController(PerformanceViewService performanceViewService,
            CurrentUserResolver currentUserResolver) {
        this.performanceViewService = performanceViewService;
        this.currentUserResolver = currentUserResolver;
    }

    @GetMapping
    public PerformanceResponse performance(
            @PathVariable long portfolioId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "DAILY") PerformanceInterval interval,
            @RequestParam(required = false) CurrencyCode currency) {
        long userId = currentUserResolver.resolve().id();

        LocalDate effectiveTo = to != null ? to : LocalDate.now(ZoneOffset.UTC);
        // Defaulted off `to`, not off today: `?from=` omitted with `?to=2020-01-01` should mean
        // the year before that date, not a five-year-wide window ending in 2020.
        LocalDate effectiveFrom = from != null ? from : effectiveTo.minusYears(DEFAULT_WINDOW_YEARS);

        return performanceViewService.get(userId, portfolioId, effectiveFrom, effectiveTo, interval, currency);
    }
}
