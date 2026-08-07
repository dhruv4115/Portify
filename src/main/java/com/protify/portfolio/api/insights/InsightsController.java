package com.protify.portfolio.api.insights;

import com.protify.portfolio.api.dto.GenerateInsightsRequest;
import com.protify.portfolio.api.dto.InsightsResponse;
import com.protify.portfolio.api.error.FeatureDisabledException;
import com.protify.portfolio.api.user.CurrentUserResolver;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API_CONTRACT.md §18, day-5-dev-C.md D5-C2 — the controller {@code InsightsClient}'s Javadoc
 * has been referring to as "Dev C's future controller" since Day 5. It checks
 * {@link InsightsViewService#isEnabled()} and answers 501 rather than calling the client at all
 * when the flag is off; the client is safe to call either way, so this is defence in depth
 * rather than the only guard.
 *
 * <p><b>Both verbs, one handler.</b> §18 specifies {@code POST} because the request carries
 * {@code horizon}/{@code tone}; the client reads this the way it reads every other panel on the
 * page, with a {@code GET} and no body. Both are honest descriptions of the same operation —
 * it is a read that happens to be parameterised, and it stores nothing — so refusing one of
 * them would be pedantry that costs a working feature. {@code GET} takes the defaults.
 *
 * <p>Errors: a portfolio belonging to someone else 404s (never 403), because
 * {@link InsightsViewService} resolves it through {@code PortfolioService#getOrThrow} before
 * building anything.
 */
@RestController
@RequestMapping("/portfolios/{portfolioId}/insights")
public class InsightsController {

    private final InsightsViewService insightsViewService;
    private final CurrentUserResolver currentUserResolver;

    public InsightsController(InsightsViewService insightsViewService, CurrentUserResolver currentUserResolver) {
        this.insightsViewService = insightsViewService;
        this.currentUserResolver = currentUserResolver;
    }

    /** The client's read path — no body, {@code 1M}/{@code concise}. */
    @GetMapping
    public InsightsResponse get(@PathVariable long portfolioId) {
        return generate(portfolioId, GenerateInsightsRequest.defaults());
    }

    /** §18's parameterised form. The body itself is optional, so {@code POST} with no body
     * behaves exactly like {@code GET} rather than 400-ing on a missing required payload. */
    @PostMapping
    public InsightsResponse post(
            @PathVariable long portfolioId,
            @Valid @RequestBody(required = false) GenerateInsightsRequest request) {
        return generate(portfolioId, request == null ? GenerateInsightsRequest.defaults() : request);
    }

    private InsightsResponse generate(long portfolioId, GenerateInsightsRequest request) {
        if (!insightsViewService.isEnabled()) {
            throw new FeatureDisabledException("/errors/feature-disabled",
                    "Portfolio insights are not enabled on this deployment.");
        }

        long userId = currentUserResolver.resolve().id();
        return insightsViewService.generate(
                userId, portfolioId, request.horizonOrDefault(), request.toneOrDefault());
    }
}
