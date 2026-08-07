package com.protify.portfolio.fx;

import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.enums.FxSource;
import com.protify.portfolio.common.money.MoneyUtils;
import com.protify.portfolio.common.port.FxRateProvider;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * D3-B3 — daily FX refresh, plus the on-demand historical backfill Dev A's performance series
 * needs: a date it has never asked for before is fetched from Frankfurter once and stored;
 * every subsequent request for that same exact date is served from {@code fx_rate}, never the
 * provider again. Never fetches or stores a future date, and never throws — a provider failure
 * here is silently absorbed, leaving whatever {@code fx_rate} already has as the last-good
 * fallback for {@link CachingFxRateService} to serve.
 */
@Component
public class FxRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(FxRefreshScheduler.class);
    private static final CurrencyCode PIVOT = CurrencyCode.USD;

    private final FxRateProvider fxRateProvider;
    private final FxRateRepository fxRateRepository;
    private final Clock clock;

    @Autowired
    public FxRefreshScheduler(FxRateProvider fxRateProvider, FxRateRepository fxRateRepository, Clock clock) {
        this.fxRateProvider = fxRateProvider;
        this.fxRateRepository = fxRateRepository;
        this.clock = clock;
    }

    @Scheduled(cron = "${fx.refresh.cron:0 15 6 * * *}")
    public void scheduledRefresh() {
        ensureRatesFor(LocalDate.now(clock));
    }

    /**
     * Fetches every non-USD currency against the USD pivot for {@code date}, but only if at
     * least one of them is genuinely missing for that exact date — a repeat call for a date
     * already fully present never reaches the provider. Refuses a future date outright: no
     * fetch, no store, so a future rate is never invented.
     */
    public void ensureRatesFor(LocalDate date) {
        LocalDate today = LocalDate.now(clock);
        if (date.isAfter(today)) {
            log.warn("Refusing to fetch or store a future FX date {}", date);
            return;
        }

        List<CurrencyCode> missing = Arrays.stream(CurrencyCode.values())
                .filter(c -> c != PIVOT)
                .filter(c -> fxRateRepository.findExact(PIVOT, c, date).isEmpty())
                .toList();
        if (missing.isEmpty()) {
            return; // already fetched this exact date once — nothing more to do
        }

        try {
            Map<CurrencyCode, BigDecimal> rates = fxRateProvider.ratesFor(PIVOT, date);
            rates.forEach((currency, rate) -> {
                if (currency != PIVOT) {
                    fxRateRepository.upsert(PIVOT, currency, date, MoneyUtils.fxRate(rate), FxSource.FRANKFURTER);
                }
            });
        } catch (RuntimeException e) {
            // Never propagate — fx_rate's last-good row (if any) remains the fallback.
            log.warn("FX provider failed while backfilling {}: {}", date, e.getMessage());
        }
    }
}
