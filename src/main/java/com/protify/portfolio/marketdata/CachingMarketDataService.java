package com.protify.portfolio.marketdata;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.enums.PriceSource;
import com.protify.portfolio.common.port.MarketDataProvider;
import com.protify.portfolio.common.port.PriceQuote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * D2-B2 — the fallback chain (ADR-0008, `/docs/ARCHITECTURE.md` §7), today's slice of it:
 *
 * <pre>
 * Caffeine (15 min TTL)
 *   → HTTP provider (adapter's own 3 s timeout, 1 retry)
 *     → price_history most recent row on or before the date
 *       → (seeded rows live in the same table, source = SEED — no separate step needed)
 * </pre>
 *
 * <p><b>Never throws on provider failure.</b> It degrades and reports the real {@code asOf}
 * date of whatever was actually served — a stale price is data with a date on it, not an error.
 * Rate limiting and a circuit breaker are tomorrow's task (D3); today the adapter's own
 * timeout/retry is the only protection, which is why nothing here calls it more than once per
 * cache miss.
 */
@Service
public class CachingMarketDataService {

    private static final Logger log = LoggerFactory.getLogger(CachingMarketDataService.class);
    private static final Duration CACHE_TTL = Duration.ofMinutes(15);

    private final MarketDataProvider marketDataProvider;
    private final PriceHistoryRepository priceHistoryRepository;
    private final InstrumentLookupRepository instrumentLookupRepository;
    private final Cache<String, PriceResult> cache;

    @Autowired
    public CachingMarketDataService(MarketDataProvider marketDataProvider,
                                     PriceHistoryRepository priceHistoryRepository,
                                     InstrumentLookupRepository instrumentLookupRepository) {
        this(marketDataProvider, priceHistoryRepository, instrumentLookupRepository,
                Caffeine.newBuilder().expireAfterWrite(CACHE_TTL).build());
    }

    /** Test-only seam: inject a cache built with a fake {@code Ticker} to prove the 15-minute
     * TTL without a real wait. */
    CachingMarketDataService(MarketDataProvider marketDataProvider,
                              PriceHistoryRepository priceHistoryRepository,
                              InstrumentLookupRepository instrumentLookupRepository,
                              Cache<String, PriceResult> cache) {
        this.marketDataProvider = marketDataProvider;
        this.priceHistoryRepository = priceHistoryRepository;
        this.instrumentLookupRepository = instrumentLookupRepository;
        this.cache = cache;
    }

    /**
     * The shape valuation calls tomorrow. Returns empty only if the provider, {@code
     * price_history} and the seed data all have nothing for this instrument — structurally
     * possible, practically rare, since {@code V11} seeds two years of history for every
     * instrument.
     */
    public Optional<PriceResult> priceFor(long instrumentId, LocalDate date) {
        String cacheKey = instrumentId + ":" + date;
        PriceResult cached = cache.getIfPresent(cacheKey);
        if (cached != null) {
            return Optional.of(cached);
        }

        Optional<PriceResult> result = resolve(instrumentId, date);
        result.ifPresent(r -> cache.put(cacheKey, r));
        return result;
    }

    private Optional<PriceResult> resolve(long instrumentId, LocalDate date) {
        Optional<InstrumentLookupRepository.InstrumentRef> instrument = instrumentLookupRepository.findById(instrumentId);
        if (instrument.isEmpty()) {
            log.warn("priceFor called for unknown instrument id {}", instrumentId);
            return fallbackToHistory(instrumentId, date);
        }

        Optional<PriceResult> fromProvider = fetchFromProvider(instrument.get(), date);
        if (fromProvider.isPresent()) {
            return fromProvider;
        }
        return fallbackToHistory(instrumentId, date);
    }

    private Optional<PriceResult> fetchFromProvider(InstrumentLookupRepository.InstrumentRef instrument, LocalDate date) {
        try {
            List<PriceQuote> quotes = marketDataProvider.dailyCloses(instrument.symbol(), date, date);
            return quotes.stream()
                    .filter(q -> q.date().equals(date))
                    .findFirst()
                    .map(q -> {
                        PriceSource source = PriceSource.fromDbValue(marketDataProvider.sourceName()).orElse(q.source());
                        priceHistoryRepository.upsert(instrument.id(), q.date(), q.close(), source);
                        return new PriceResult(q.close(), q.currency(), q.date(), source);
                    });
        } catch (RuntimeException e) {
            // Adapters are documented to never throw, but the service itself must never
            // propagate a provider failure either way — belt and suspenders.
            log.warn("Market data provider failed for {}: {}", instrument.symbol(), e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<PriceResult> fallbackToHistory(long instrumentId, LocalDate date) {
        return priceHistoryRepository.mostRecentOnOrBefore(instrumentId, date)
                .map(row -> new PriceResult(row.closePrice(), currencyFor(instrumentId), row.priceDate(), row.source()));
    }

    private CurrencyCode currencyFor(long instrumentId) {
        return instrumentLookupRepository.findById(instrumentId)
                .map(InstrumentLookupRepository.InstrumentRef::currency)
                .orElse(CurrencyCode.USD);
    }
}
