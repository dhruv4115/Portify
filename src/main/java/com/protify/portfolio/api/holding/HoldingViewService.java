package com.protify.portfolio.api.holding;

import com.protify.portfolio.api.dto.HoldingResponse;
import com.protify.portfolio.api.mapper.HoldingMapper;
import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.port.FxQuote;
import com.protify.portfolio.fx.CachingFxRateService;
import com.protify.portfolio.holding.Holding;
import com.protify.portfolio.holding.ProjectionContext;
import com.protify.portfolio.instrument.Instrument;
import com.protify.portfolio.instrument.InstrumentRepository;
import com.protify.portfolio.marketdata.CachingMarketDataService;
import com.protify.portfolio.marketdata.PriceResult;
import com.protify.portfolio.holding.HoldingRepository;
import com.protify.portfolio.portfolio.Portfolio;
import com.protify.portfolio.portfolio.PortfolioService;
import com.protify.portfolio.transaction.TransactionRepository;
import com.protify.portfolio.valuation.ValuationFolder;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The customer's "browse" (day-3-dev-C.md D3-C2), API_CONTRACT.md §10. Three sources, each
 * authoritative for a different column, which is the whole reason this class exists rather than a
 * single repository call:
 *
 * <ul>
 *   <li>{@code holding} — {@code quantity} and {@code avgCost}, native and never converted
 *       (CLAUDE.md non-negotiable #13)</li>
 *   <li>{@link ValuationFolder} over the transaction history — {@code costBasis} and
 *       {@code realisedPnl} in the target currency at each trade's <em>own</em> FX rate, which is
 *       what separates currency P&amp;L from market P&amp;L (ADR-0011). Reused rather than
 *       reimplemented so this endpoint and {@code /valuation} cannot disagree on cost basis.</li>
 *   <li>Dev B's caching services — {@code lastPrice} (native) and the {@code asOf} rate used to
 *       express market value in the target currency</li>
 * </ul>
 *
 * <p>A position that cannot be priced yields {@code null} money fields and a stale
 * {@code dataQuality} rather than an error: one unpriceable instrument must not blank the
 * portfolio (TEST_PLAN.md §4.3). Every path resolves the portfolio through
 * {@link PortfolioService#getOrThrow} first, so another user's is a 404, never a 403.
 */
@Service
public class HoldingViewService {

    private static final Logger log = LoggerFactory.getLogger(HoldingViewService.class);

    private final PortfolioService portfolioService;
    private final HoldingRepository holdingRepository;
    private final TransactionRepository transactionRepository;
    private final InstrumentRepository instrumentRepository;
    private final CachingMarketDataService marketDataService;
    private final CachingFxRateService fxRateService;
    private final HoldingMapper holdingMapper;

    public HoldingViewService(
            PortfolioService portfolioService,
            HoldingRepository holdingRepository,
            TransactionRepository transactionRepository,
            InstrumentRepository instrumentRepository,
            CachingMarketDataService marketDataService,
            CachingFxRateService fxRateService,
            HoldingMapper holdingMapper) {
        this.portfolioService = portfolioService;
        this.holdingRepository = holdingRepository;
        this.transactionRepository = transactionRepository;
        this.instrumentRepository = instrumentRepository;
        this.marketDataService = marketDataService;
        this.fxRateService = fxRateService;
        this.holdingMapper = holdingMapper;
    }

    /**
     * @param requestedCurrency presentation override; {@code null} means the portfolio's base
     *                          currency. Nothing stored changes either way (§10).
     * @param includeZero       {@code true} keeps positions sold down to zero, which the
     *                          projection deliberately retains so their realised P&amp;L survives
     */
    public List<HoldingResponse> list(long userId, long portfolioId, CurrencyCode requestedCurrency,
            boolean includeZero) {
        Portfolio portfolio = portfolioService.getOrThrow(userId, portfolioId);
        CurrencyCode currency = requestedCurrency != null ? requestedCurrency : portfolio.baseCurrency();
        LocalDate asOf = LocalDate.now(ZoneOffset.UTC);

        List<HoldingValuation> valued = valuations(portfolioId, currency, asOf);
        if (valued.isEmpty()) {
            // 200 [], never a 404 — an empty portfolio is a normal answer (TEST_PLAN.md §4.9).
            return List.of();
        }

        // Weights are a share of the whole portfolio, so the total is taken before the
        // includeZero filter — a closed position contributes nothing to it anyway.
        BigDecimal totalMarketValue = valued.stream()
                .map(HoldingValuation::marketValue)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return valued.stream()
                .filter(v -> includeZero || v.holding().quantity().signum() != 0)
                .map(v -> holdingMapper.toResponse(v, totalMarketValue, currency, asOf))
                .toList();
    }

    /**
     * The priced positions behind {@link #list}, before any DTO shaping — extracted so the
     * insights summary can be built from the same numbers this endpoint renders, rather than a
     * second implementation of the same fold that could drift out of step with it.
     *
     * <p>Resolves the portfolio through {@link PortfolioService#getOrThrow} exactly as
     * {@link #list} does, so an unowned portfolio is still a 404 for every caller.
     */
    public List<HoldingValuation> valuations(long userId, long portfolioId, CurrencyCode requestedCurrency) {
        Portfolio portfolio = portfolioService.getOrThrow(userId, portfolioId);
        CurrencyCode currency = requestedCurrency != null ? requestedCurrency : portfolio.baseCurrency();
        return valuations(portfolioId, currency, LocalDate.now(ZoneOffset.UTC));
    }

    private List<HoldingValuation> valuations(long portfolioId, CurrencyCode currency, LocalDate asOf) {
        List<Holding> holdings = holdingRepository.findByPortfolio(portfolioId);
        if (holdings.isEmpty()) {
            return List.of();
        }

        ValuationFolder.Snapshot snapshot = ValuationFolder.fold(
                transactionRepository.findByPortfolioOrderByExecutedAt(portfolioId), tradeDateFx(currency));

        List<HoldingValuation> valued = new ArrayList<>();
        for (Holding holding : holdings) {
            value(holding, snapshot, currency, asOf).ifPresent(valued::add);
        }
        return valued;
    }

    private Optional<HoldingValuation> value(Holding holding, ValuationFolder.Snapshot snapshot,
            CurrencyCode currency, LocalDate asOf) {
        Optional<Instrument> instrument = instrumentRepository.findById(holding.instrumentId());
        if (instrument.isEmpty()) {
            // A holding whose instrument row has vanished cannot be rendered at all; dropping the
            // row beats failing the whole response, and the log is how anyone finds out.
            log.warn("Holding {} references unknown instrument {}", holding.id(), holding.instrumentId());
            return Optional.empty();
        }

        ValuationFolder.InstrumentPosition position = snapshot.positions().get(holding.instrumentId());
        BigDecimal costBasis = position == null ? BigDecimal.ZERO : position.costBasis();
        BigDecimal realisedPnl = position == null ? BigDecimal.ZERO : position.realisedPnl();

        Optional<PriceResult> price = marketDataService.priceFor(holding.instrumentId(), asOf);
        Optional<FxQuote> fx = fxRateService.rate(instrument.get().currency(), currency, asOf);

        BigDecimal marketValue = (price.isPresent() && fx.isPresent())
                ? holding.quantity().multiply(price.get().price()).multiply(fx.get().rate())
                : null;

        return Optional.of(new HoldingValuation(
                holding,
                instrument.get(),
                price.map(PriceResult::price).orElse(null),
                marketValue,
                costBasis,
                realisedPnl,
                fx.map(FxQuote::rate).orElse(null),
                price.map(PriceResult::asOf).orElse(null),
                fx.map(FxQuote::date).orElse(null)));
    }

    /** Cost basis is folded at each transaction's own rate, never today's — that difference is
     * the currency P&amp;L (ADR-0011). Identity on an unresolvable rate, matching the
     * {@link ProjectionContext.FxLookup} contract that it never returns {@code null}. */
    private ProjectionContext.FxLookup tradeDateFx(CurrencyCode target) {
        return (from, on) -> fxRateService.rate(from, target, on)
                .map(FxQuote::rate)
                .orElse(BigDecimal.ONE);
    }
}
