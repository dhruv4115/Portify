package com.protify.portfolio.valuation.spi;

import com.protify.portfolio.marketdata.CachingMarketDataService;
import com.protify.portfolio.marketdata.PriceHistoryRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The single, audited seam between the valuation layer and Dev B's {@code marketdata} package.
 * {@code ArchitectureTest#coreMustNotDependOnPlatform} exempts {@code *Adapter} precisely so
 * that this coupling is one grep away from being reviewed, rather than spread through the
 * business logic — which is what it was before Day 4.
 *
 * <p>Nothing but translation happens here. Both fallback chains (Caffeine → provider →
 * {@code price_history} → seed) live behind {@link CachingMarketDataService}; adding a decision
 * to this class would put untested logic in the one place the architecture rule cannot see.
 */
@Component
class MarketDataPriceLookupAdapter implements PriceLookup {

    private final CachingMarketDataService marketDataService;
    private final PriceHistoryRepository priceHistoryRepository;

    MarketDataPriceLookupAdapter(CachingMarketDataService marketDataService,
            PriceHistoryRepository priceHistoryRepository) {
        this.marketDataService = marketDataService;
        this.priceHistoryRepository = priceHistoryRepository;
    }

    @Override
    public Optional<PricePoint> priceOn(long instrumentId, LocalDate date) {
        return marketDataService.priceFor(instrumentId, date)
                .map(result -> new PricePoint(result.price(), result.currency(), result.asOf()));
    }

    @Override
    public Map<Long, NavigableMap<LocalDate, BigDecimal>> seriesUpTo(Collection<Long> instrumentIds, LocalDate to) {
        return priceHistoryRepository.findRange(instrumentIds, to);
    }
}
