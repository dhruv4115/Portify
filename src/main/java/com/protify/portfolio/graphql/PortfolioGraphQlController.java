package com.protify.portfolio.graphql;

import com.protify.portfolio.api.allocation.AllocationService;
import com.protify.portfolio.api.dto.AllocationDimension;
import com.protify.portfolio.api.dto.AllocationResponse;
import com.protify.portfolio.api.dto.HoldingResponse;
import com.protify.portfolio.api.dto.PerformanceInterval;
import com.protify.portfolio.api.dto.PerformanceResponse;
import com.protify.portfolio.api.dto.PortfolioResponse;
import com.protify.portfolio.api.holding.HoldingViewService;
import com.protify.portfolio.api.mapper.PortfolioMapper;
import com.protify.portfolio.api.portfolio.PortfolioSummaryProvider;
import com.protify.portfolio.api.user.CurrentUserResolver;
import com.protify.portfolio.api.valuation.PerformanceViewService;
import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.error.NotFoundException;
import com.protify.portfolio.portfolio.Portfolio;
import com.protify.portfolio.portfolio.PortfolioService;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

/**
 * day-5-dev-C.md D5-C1. Every method calls the same api/-layer service the equivalent REST
 * controller calls — {@link PortfolioService}/{@link PortfolioSummaryProvider} (see
 * {@code PortfolioController}), {@link HoldingViewService} (see {@code HoldingController}),
 * {@link PerformanceViewService} (see {@code PerformanceController}), {@link AllocationService}
 * (see {@code AllocationController}) — never a repository directly, so an authorisation bug
 * cannot exist in one API and not the other.
 *
 * <p>{@link #portfolio} is the only root query. Everything else — {@code holdings}, {@code
 * performance}, {@code allocation} — is a field on the {@code Portfolio} type it returns, which
 * is what makes the dashboard query "portfolio + holdings + performance + allocation in one
 * round trip" a plain nested selection rather than a bespoke aggregate endpoint.
 */
@Controller
public class PortfolioGraphQlController {

    private final PortfolioService portfolioService;
    private final PortfolioSummaryProvider portfolioSummaryProvider;
    private final PortfolioMapper portfolioMapper;
    private final HoldingViewService holdingViewService;
    private final PerformanceViewService performanceViewService;
    private final AllocationService allocationService;
    private final CurrentUserResolver currentUserResolver;

    public PortfolioGraphQlController(
            PortfolioService portfolioService,
            PortfolioSummaryProvider portfolioSummaryProvider,
            PortfolioMapper portfolioMapper,
            HoldingViewService holdingViewService,
            PerformanceViewService performanceViewService,
            AllocationService allocationService,
            CurrentUserResolver currentUserResolver) {
        this.portfolioService = portfolioService;
        this.portfolioSummaryProvider = portfolioSummaryProvider;
        this.portfolioMapper = portfolioMapper;
        this.holdingViewService = holdingViewService;
        this.performanceViewService = performanceViewService;
        this.allocationService = allocationService;
        this.currentUserResolver = currentUserResolver;
    }

    /**
     * Null for another user's portfolio (or one that doesn't exist) — the GraphQL equivalent of
     * REST's 404, never an error. {@code portfolio(id:)} is deliberately the only seam between
     * "the id belongs to this user" and everything nested under it: {@link #holdings},
     * {@link #performance} and {@link #allocation} all re-validate ownership themselves too
     * (each calls {@link PortfolioService#getOrThrow} again through their own view service), so
     * there is no path that trusts an unauthorised id just because a sibling field already
     * checked it.
     */
    @QueryMapping
    public PortfolioResponse portfolio(@Argument Long id) {
        long userId = currentUserResolver.resolve().id();
        try {
            Portfolio found = portfolioService.getOrThrow(userId, id);
            return portfolioMapper.toResponse(found, portfolioSummaryProvider.summarize(found));
        } catch (NotFoundException e) {
            return null;
        }
    }

    @SchemaMapping(typeName = "Portfolio", field = "holdings")
    public List<HoldingResponse> holdings(PortfolioResponse portfolio,
            @Argument CurrencyCode currency, @Argument Boolean includeZero) {
        long userId = currentUserResolver.resolve().id();
        return holdingViewService.list(userId, portfolio.id(), currency, Boolean.TRUE.equals(includeZero));
    }

    @SchemaMapping(typeName = "Portfolio", field = "performance")
    public PerformanceResponse performance(PortfolioResponse portfolio,
            @Argument LocalDate from, @Argument LocalDate to,
            @Argument PerformanceInterval interval, @Argument CurrencyCode currency) {
        long userId = currentUserResolver.resolve().id();
        LocalDate effectiveTo = to != null ? to : LocalDate.now(ZoneOffset.UTC);
        LocalDate effectiveFrom = from != null ? from : effectiveTo.minusYears(1);
        PerformanceInterval effectiveInterval = interval != null ? interval : PerformanceInterval.DAILY;
        return performanceViewService.get(userId, portfolio.id(), effectiveFrom, effectiveTo, effectiveInterval, currency);
    }

    @SchemaMapping(typeName = "Portfolio", field = "allocation")
    public AllocationResponse allocation(PortfolioResponse portfolio,
            @Argument AllocationDimension by, @Argument CurrencyCode currency) {
        long userId = currentUserResolver.resolve().id();
        AllocationDimension effectiveBy = by != null ? by : AllocationDimension.ASSET_TYPE;
        return allocationService.compute(userId, portfolio.id(), effectiveBy, currency);
    }
}
