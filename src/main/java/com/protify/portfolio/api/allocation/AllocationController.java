package com.protify.portfolio.api.allocation;

import com.protify.portfolio.api.dto.AllocationDimension;
import com.protify.portfolio.api.dto.AllocationResponse;
import com.protify.portfolio.api.user.CurrentUserResolver;
import com.protify.portfolio.common.enums.CurrencyCode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API_CONTRACT.md §13. Validate, delegate, map — an unknown {@code by}/{@code currency} is
 * rejected by enum binding before this method runs, turned into a 400 by
 * {@code GlobalExceptionHandler}; there is no {@code if} here to keep in sync with either enum.
 */
@RestController
@RequestMapping("/portfolios/{portfolioId}/allocation")
@Validated
public class AllocationController {

    /**
     * A pie with fifty wedges is a colour wheel, not a chart, so {@code limit} is capped well
     * below any number that could produce one. The floor is 2 because "the top 1 and everything
     * else" is the smallest fold that says anything, and a limit of 1 would be a single wedge
     * labelled "Other" — a picture of nothing.
     */
    private static final int MIN_LIMIT = 2;
    private static final int MAX_LIMIT = 50;

    private final AllocationService allocationService;
    private final CurrentUserResolver currentUserResolver;

    public AllocationController(AllocationService allocationService, CurrentUserResolver currentUserResolver) {
        this.allocationService = allocationService;
        this.currentUserResolver = currentUserResolver;
    }

    /**
     * @param limit optional. Returns at most this many slices, the smallest folded into a single
     *              {@code OTHER}. Absent means every slice, which is what the endpoint has always
     *              done — the parameter is opt-in so no existing caller changes behaviour.
     */
    @GetMapping
    public AllocationResponse get(
            @PathVariable long portfolioId,
            @RequestParam(defaultValue = "ASSET_TYPE") AllocationDimension by,
            @RequestParam(required = false) CurrencyCode currency,
            @RequestParam(required = false) @Min(MIN_LIMIT) @Max(MAX_LIMIT) Integer limit) {
        long userId = currentUserResolver.resolve().id();
        return allocationService.compute(userId, portfolioId, by, currency, limit);
    }
}
