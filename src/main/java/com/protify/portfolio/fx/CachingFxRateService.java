package com.protify.portfolio.fx;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.enums.FxSource;
import com.protify.portfolio.common.money.Money;
import com.protify.portfolio.common.money.MoneyUtils;
import com.protify.portfolio.common.port.FxQuote;
import com.protify.portfolio.common.port.FxRateProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * D2-B3 — FX fallback chain: Caffeine (6 h TTL) → provider → {@code fx_rate} last-good row
 * (which is also where the {@code V12} seed data lives, so "seeded" needs no separate step).
 * Stores and reads a **USD pivot** (ADR-0011): a cross rate is
 * {@code rate(USD→quote) ÷ rate(USD→base)}. Never hard-codes a rate, never throws on provider
 * failure, never returns a future rate.
 */
@Service
public class CachingFxRateService {

    private static final Logger log = LoggerFactory.getLogger(CachingFxRateService.class);
    private static final Duration CACHE_TTL = Duration.ofHours(6);
    private static final CurrencyCode PIVOT = CurrencyCode.USD;
    private static final BigDecimal ONE = BigDecimal.ONE.setScale(8, RoundingMode.HALF_UP);

    private final FxRateProvider fxRateProvider;
    private final FxRateRepository fxRateRepository;
    private final Cache<String, FxQuote> cache;

    @Autowired
    public CachingFxRateService(FxRateProvider fxRateProvider, FxRateRepository fxRateRepository) {
        this(fxRateProvider, fxRateRepository, Caffeine.newBuilder().expireAfterWrite(CACHE_TTL).build());
    }

    /** Test-only seam, mirrors {@link com.protify.portfolio.marketdata.CachingMarketDataService}. */
    CachingFxRateService(FxRateProvider fxRateProvider, FxRateRepository fxRateRepository,
                          Cache<String, FxQuote> cache) {
        this.fxRateProvider = fxRateProvider;
        this.fxRateRepository = fxRateRepository;
        this.cache = cache;
    }

    public Optional<FxQuote> rate(CurrencyCode from, CurrencyCode to, LocalDate date) {
        if (from == to) {
            return Optional.of(new FxQuote(from, to, date, ONE, FxSource.MANUAL));
        }

        String cacheKey = from.name() + ":" + to.name() + ":" + date;
        FxQuote cached = cache.getIfPresent(cacheKey);
        if (cached != null) {
            return Optional.of(cached);
        }

        refreshPivotFromProvider(date);

        Optional<FxQuote> result = crossRate(from, to, date);
        result.ifPresent(q -> cache.put(cacheKey, q));
        return result;
    }

    /** The shape valuation calls tomorrow: converted value plus the date the rate actually
     * applies to. */
    public Optional<ConversionResult> convert(Money money, CurrencyCode target, LocalDate date) {
        if (money.currency() == target) {
            return Optional.of(ConversionResult.identity(money, date));
        }
        return rate(money.currency(), target, date)
                .map(q -> new ConversionResult(MoneyUtils.money(money.amount().multiply(q.rate())), target, q.date()));
    }

    private void refreshPivotFromProvider(LocalDate date) {
        // D3-B3: fetch this exact date at most once — if fx_rate already has every non-USD
        // pivot row for it (from an earlier call, the scheduler, or an on-demand backfill via
        // FxRefreshScheduler), a subsequent 6h-cache-expiry miss must not call the provider
        // again for data it already has.
        boolean alreadyHaveExactDate = java.util.Arrays.stream(CurrencyCode.values())
                .filter(c -> c != PIVOT)
                .allMatch(c -> fxRateRepository.findExact(PIVOT, c, date).isPresent());
        if (alreadyHaveExactDate) {
            return;
        }

        try {
            Map<CurrencyCode, BigDecimal> rates = fxRateProvider.ratesFor(PIVOT, date);
            rates.forEach((currency, rate) -> {
                if (currency != PIVOT) {
                    fxRateRepository.upsert(PIVOT, currency, date, MoneyUtils.fxRate(rate), FxSource.FRANKFURTER);
                }
            });
        } catch (RuntimeException e) {
            // Never propagate a provider failure — fall through to whatever fx_rate already has.
            log.warn("FX provider failed for {}: {}", date, e.getMessage());
        }
    }

    private Optional<FxQuote> crossRate(CurrencyCode from, CurrencyCode to, LocalDate date) {
        Optional<FxRateRow> fromPivot = pivotRate(from, date);
        Optional<FxRateRow> toPivot = pivotRate(to, date);
        if (fromPivot.isEmpty() || toPivot.isEmpty()) {
            return Optional.empty();
        }

        BigDecimal cross = MoneyUtils.fxRate(
                toPivot.get().rate().divide(fromPivot.get().rate(), 16, RoundingMode.HALF_UP));
        // The cross rate is only as fresh as its stalest component.
        LocalDate asOf = fromPivot.get().rateDate().isBefore(toPivot.get().rateDate())
                ? fromPivot.get().rateDate() : toPivot.get().rateDate();
        FxSource source = (fromPivot.get().source() == FxSource.SEED || toPivot.get().source() == FxSource.SEED)
                ? FxSource.SEED : FxSource.FRANKFURTER;

        return Optional.of(new FxQuote(from, to, asOf, cross, source));
    }

    private Optional<FxRateRow> pivotRate(CurrencyCode currency, LocalDate date) {
        if (currency == PIVOT) {
            return Optional.of(new FxRateRow(PIVOT, PIVOT, date, ONE, FxSource.MANUAL));
        }
        return fxRateRepository.mostRecentOnOrBefore(PIVOT, currency, date);
    }
}
