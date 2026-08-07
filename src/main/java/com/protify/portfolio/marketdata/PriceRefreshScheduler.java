package com.protify.portfolio.marketdata;

import com.protify.portfolio.common.port.MarketDataProvider;
import com.protify.portfolio.common.port.PriceQuote;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * D3-B1 — daily scheduled price refresh, plus the service behind {@code POST
 * /admin/prices/refresh} (Dev C exposes the endpoint; this is what it calls).
 *
 * <p><b>The governing rule (ADR-0008, RISKS.md R4): providers are invoked only from here — by
 * the scheduled job or the explicit admin trigger — never from a read path.</b> Everything
 * that reaches {@link #marketDataProvider} is the {@code @Primary} {@code RateLimitedProvider}
 * (D3-B2), so even a burst of admin-triggered refreshes across every seeded instrument stays
 * inside the token bucket.
 *
 * <p>Partial success is the normal outcome, not an error (API_CONTRACT.md §17): a failure for
 * one symbol is caught and reported per-symbol, and never stops the rest of the batch or
 * propagates out of {@link #refresh}.
 */
@Component
public class PriceRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(PriceRefreshScheduler.class);

    /** No stored history at all yet — grab a conservative week rather than the whole two-year
     * seed window on every miss. */
    private static final int DEFAULT_BACKFILL_DAYS = 7;

    private final MarketDataProvider marketDataProvider;
    private final PriceHistoryRepository priceHistoryRepository;
    private final InstrumentLookupRepository instrumentLookupRepository;
    private final Clock clock;
    private final Set<String> adminEmails;

    @Autowired
    public PriceRefreshScheduler(MarketDataProvider marketDataProvider,
                                  PriceHistoryRepository priceHistoryRepository,
                                  InstrumentLookupRepository instrumentLookupRepository,
                                  Clock clock,
                                  @Value("${admin.emails:}") String adminEmailsProperty) {
        this.marketDataProvider = marketDataProvider;
        this.priceHistoryRepository = priceHistoryRepository;
        this.instrumentLookupRepository = instrumentLookupRepository;
        this.clock = clock;
        this.adminEmails = parseAdminEmails(adminEmailsProperty);
    }

    private static Set<String> parseAdminEmails(String property) {
        if (property == null || property.isBlank()) {
            return Set.of();
        }
        return java.util.Arrays.stream(property.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    @Scheduled(cron = "${marketdata.refresh.cron:0 0 6 * * *}")
    public void scheduledRefresh() {
        RefreshPricesResult result = refresh(null, null);
        log.info("Scheduled price refresh: requested={} updated={} skipped={} failed={}",
                result.requested(), result.updated(), result.skipped(), result.failed());
    }

    /** Restricted to {@code ADMIN_EMAILS} — anyone else gets 403 (via the existing
     * {@code GlobalExceptionHandler}'s {@code AccessDeniedException} handler). */
    public RefreshPricesResult adminRefresh(List<String> symbolsOrNull, LocalDate fromOrNull, String callerEmail) {
        String normalised = callerEmail == null ? "" : callerEmail.strip().toLowerCase(Locale.ROOT);
        if (!adminEmails.contains(normalised)) {
            throw new AccessDeniedException("Not permitted to trigger a price refresh");
        }
        return refresh(symbolsOrNull, fromOrNull);
    }

    /** {@code symbolsOrNull == null} means every seeded instrument. Never throws — a failure
     * for one symbol becomes a {@code FAILED} entry, not an aborted batch. */
    public RefreshPricesResult refresh(List<String> symbolsOrNull, LocalDate fromOrNull) {
        List<InstrumentLookupRepository.InstrumentRef> targets = resolveTargets(symbolsOrNull);

        List<SymbolRefreshResult> results = new ArrayList<>();
        for (InstrumentLookupRepository.InstrumentRef instrument : targets) {
            results.add(refreshOne(instrument, fromOrNull));
        }

        int updated = countByStatus(results, RefreshStatus.UPDATED);
        int skipped = countByStatus(results, RefreshStatus.SKIPPED);
        int failed = countByStatus(results, RefreshStatus.FAILED);
        return new RefreshPricesResult(targets.size(), updated, skipped, failed, results);
    }

    private List<InstrumentLookupRepository.InstrumentRef> resolveTargets(List<String> symbolsOrNull) {
        List<InstrumentLookupRepository.InstrumentRef> all = instrumentLookupRepository.findAll();
        if (symbolsOrNull == null || symbolsOrNull.isEmpty()) {
            return all;
        }
        Set<String> wanted = symbolsOrNull.stream()
                .map(s -> s.strip().toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
        return all.stream()
                .filter(i -> wanted.contains(i.symbol().toUpperCase(Locale.ROOT)))
                .toList();
    }

    private SymbolRefreshResult refreshOne(InstrumentLookupRepository.InstrumentRef instrument, LocalDate fromOrNull) {
        try {
            LocalDate to = LocalDate.now(clock);
            LocalDate from = fromOrNull != null ? fromOrNull : nextFetchStart(instrument, to);
            if (from.isAfter(to)) {
                return new SymbolRefreshResult(instrument.symbol(), RefreshStatus.SKIPPED, 0, null, null);
            }

            List<PriceQuote> quotes = marketDataProvider.dailyCloses(instrument.symbol(), from, to);
            if (quotes.isEmpty()) {
                return new SymbolRefreshResult(instrument.symbol(), RefreshStatus.SKIPPED, 0, null, null);
            }

            for (PriceQuote quote : quotes) {
                priceHistoryRepository.upsert(instrument.id(), quote.date(), quote.close(), quote.source());
            }
            PriceQuote latest = quotes.stream().max(Comparator.comparing(PriceQuote::date)).orElseThrow();
            return new SymbolRefreshResult(instrument.symbol(), RefreshStatus.UPDATED, quotes.size(),
                    latest.date(), latest.source().name());
        } catch (RuntimeException e) {
            // A failure for one symbol must never abort the batch (API_CONTRACT.md §17).
            log.warn("Price refresh failed for {}: {}", instrument.symbol(), e.getMessage());
            return new SymbolRefreshResult(instrument.symbol(), RefreshStatus.FAILED, 0, null, null);
        }
    }

    private LocalDate nextFetchStart(InstrumentLookupRepository.InstrumentRef instrument, LocalDate today) {
        return priceHistoryRepository.mostRecentOnOrBefore(instrument.id(), today)
                .map(row -> row.priceDate().plusDays(1))
                .orElse(today.minusDays(DEFAULT_BACKFILL_DAYS));
    }

    private static int countByStatus(List<SymbolRefreshResult> results, RefreshStatus status) {
        return (int) results.stream().filter(r -> r.status() == status).count();
    }
}
