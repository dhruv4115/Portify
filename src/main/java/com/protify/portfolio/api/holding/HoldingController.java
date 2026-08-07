package com.protify.portfolio.api.holding;

import com.protify.portfolio.api.dto.HoldingResponse;
import com.protify.portfolio.api.user.CurrentUserResolver;
import com.protify.portfolio.common.enums.CurrencyCode;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The customer's "browse" (day-3-dev-C.md D3-C2), API_CONTRACT.md §10. Validate, delegate, map —
 * every figure comes from {@link HoldingViewService}.
 *
 * <p>An unknown {@code currency} is rejected by enum binding before this method runs, which
 * {@code GlobalExceptionHandler} turns into a 400; there is no {@code if} here to keep in sync
 * with {@link CurrencyCode}'s values.
 */
@RestController
@RequestMapping("/portfolios/{portfolioId}/holdings")
public class HoldingController {

    private final HoldingViewService holdingViewService;
    private final CurrentUserResolver currentUserResolver;

    public HoldingController(HoldingViewService holdingViewService, CurrentUserResolver currentUserResolver) {
        this.holdingViewService = holdingViewService;
        this.currentUserResolver = currentUserResolver;
    }

    /**
     * @param includeZero defaults to {@code false}: a position sold to zero keeps its row so its
     *                    realised P&amp;L survives, but it is not something the customer wants in
     *                    a list of what they own
     */
    @GetMapping
    public List<HoldingResponse> list(
            @PathVariable long portfolioId,
            @RequestParam(required = false) CurrencyCode currency,
            @RequestParam(defaultValue = "false") boolean includeZero) {
        long userId = currentUserResolver.resolve().id();
        return holdingViewService.list(userId, portfolioId, currency, includeZero);
    }
}
