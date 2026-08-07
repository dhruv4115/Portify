package com.protify.portfolio.api.overview;

import com.protify.portfolio.api.dto.OverviewResponse;
import com.protify.portfolio.api.user.CurrentUserResolver;
import com.protify.portfolio.common.enums.CurrencyCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/overview} — the whole account in one call, for the landing page.
 *
 * <p>The aggregation lives on this side of the wire because only this side can do it honestly:
 * summing across portfolios with different base currencies needs a dated FX rate, and the
 * browser has none. See {@link OverviewViewService} for how the two totals are built and
 * {@link OverviewResponse} for why both are reported.
 *
 * <p>Never 404s. An account with no portfolios answers 200 with zeroes and an empty
 * {@code byCurrency} — the same rule {@code GET /portfolios} follows.
 */
@RestController
@RequestMapping("/overview")
public class OverviewController {

    private final OverviewViewService overviewViewService;
    private final CurrentUserResolver currentUserResolver;

    public OverviewController(OverviewViewService overviewViewService, CurrentUserResolver currentUserResolver) {
        this.overviewViewService = overviewViewService;
        this.currentUserResolver = currentUserResolver;
    }

    /**
     * @param currency display currency for the grand total. Omitted means "whichever currency
     *                 most of your portfolios already use" — a presentation override only,
     *                 exactly like {@code ?currency=} on §10/§11; nothing stored changes.
     */
    @GetMapping
    public OverviewResponse get(@RequestParam(required = false) CurrencyCode currency) {
        long userId = currentUserResolver.resolve().id();
        return overviewViewService.overview(userId, currency);
    }
}
