package com.protify.portfolio.api.valuation;

import com.protify.portfolio.api.dto.ValuationResponse;
import com.protify.portfolio.api.mapper.ValuationMapper;
import com.protify.portfolio.api.user.CurrentUserResolver;
import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.valuation.ValuationService;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API_CONTRACT.md §11 (day-3-dev-C.md D3-C2). Validate, delegate, map: every number is Dev A's
 * {@link ValuationService}, and the two rules that can fail — {@code asOf} earlier than the first
 * transaction, and an unknown {@code currency} — surface as 400s from that service and from enum
 * binding respectively, not from an {@code if} here.
 */
@RestController
@RequestMapping("/portfolios/{portfolioId}/valuation")
public class ValuationController {

    private final ValuationService valuationService;
    private final ValuationMapper valuationMapper;
    private final CurrentUserResolver currentUserResolver;

    public ValuationController(ValuationService valuationService, ValuationMapper valuationMapper,
            CurrentUserResolver currentUserResolver) {
        this.valuationService = valuationService;
        this.valuationMapper = valuationMapper;
        this.currentUserResolver = currentUserResolver;
    }

    /**
     * @param asOf defaults to today in <b>UTC</b> (CLAUDE.md non-negotiable #9), not the server's
     *             local zone — a valuation must not shift by a day depending on where it runs
     */
    @GetMapping
    public ValuationResponse valuate(
            @PathVariable long portfolioId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(required = false) CurrencyCode currency) {
        long userId = currentUserResolver.resolve().id();
        LocalDate effectiveAsOf = asOf != null ? asOf : LocalDate.now(ZoneOffset.UTC);

        return valuationMapper.toResponse(
                valuationService.valuate(userId, portfolioId, effectiveAsOf, currency));
    }
}
