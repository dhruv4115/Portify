package com.protify.portfolio.marketdata;

import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.enums.PriceSource;
import com.protify.portfolio.common.port.MarketDataProvider;
import com.protify.portfolio.common.port.PriceQuote;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D3-B1 — {@code PriceRefreshSchedulerTest}: every collaborator mocked; no test calls a real
 * provider or a real database.
 */
@ExtendWith(MockitoExtension.class)
class PriceRefreshSchedulerTest {

    @Mock
    private MarketDataProvider marketDataProvider;
    @Mock
    private PriceHistoryRepository priceHistoryRepository;
    @Mock
    private InstrumentLookupRepository instrumentLookupRepository;

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-10T00:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 1, 10);

    private static final InstrumentLookupRepository.InstrumentRef AAPL =
            new InstrumentLookupRepository.InstrumentRef(1L, "AAPL", CurrencyCode.USD);
    private static final InstrumentLookupRepository.InstrumentRef MSFT =
            new InstrumentLookupRepository.InstrumentRef(2L, "MSFT", CurrencyCode.USD);
    private static final InstrumentLookupRepository.InstrumentRef RELIANCE =
            new InstrumentLookupRepository.InstrumentRef(3L, "RELIANCE", CurrencyCode.INR);

    private PriceRefreshScheduler scheduler(String adminEmails) {
        return new PriceRefreshScheduler(marketDataProvider, priceHistoryRepository,
                instrumentLookupRepository, CLOCK, adminEmails);
    }

    @Test
    void aProviderFailureOnOneSymbolDoesNotAbortTheRest() {
        when(instrumentLookupRepository.findAll()).thenReturn(List.of(AAPL, MSFT, RELIANCE));
        when(priceHistoryRepository.mostRecentOnOrBefore(1L, TODAY)).thenReturn(Optional.empty());
        when(priceHistoryRepository.mostRecentOnOrBefore(2L, TODAY)).thenReturn(Optional.empty());
        when(priceHistoryRepository.mostRecentOnOrBefore(3L, TODAY)).thenReturn(Optional.empty());

        when(marketDataProvider.dailyCloses("AAPL", TODAY.minusDays(7), TODAY))
                .thenReturn(List.of(new PriceQuote("AAPL", TODAY, new BigDecimal("190.0000"), CurrencyCode.USD, PriceSource.YAHOO)));
        when(marketDataProvider.dailyCloses("MSFT", TODAY.minusDays(7), TODAY))
                .thenThrow(new RuntimeException("upstream down"));
        when(marketDataProvider.dailyCloses("RELIANCE", TODAY.minusDays(7), TODAY))
                .thenReturn(List.of(new PriceQuote("RELIANCE", TODAY, new BigDecimal("2900.0000"), CurrencyCode.INR, PriceSource.YAHOO)));

        RefreshPricesResult result = scheduler("").refresh(null, null);

        assertThat(result.requested()).isEqualTo(3);
        assertThat(result.updated()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(0);
        assertThat(result.results()).extracting(SymbolRefreshResult::symbol, SymbolRefreshResult::status)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("AAPL", RefreshStatus.UPDATED),
                        org.assertj.core.groups.Tuple.tuple("MSFT", RefreshStatus.FAILED),
                        org.assertj.core.groups.Tuple.tuple("RELIANCE", RefreshStatus.UPDATED));

        verify(priceHistoryRepository).upsert(1L, TODAY, new BigDecimal("190.0000"), PriceSource.YAHOO);
        verify(priceHistoryRepository).upsert(3L, TODAY, new BigDecimal("2900.0000"), PriceSource.YAHOO);
    }

    @Test
    void adminRefreshRejectsACallerNotInAdminEmails() {
        PriceRefreshScheduler scheduler = scheduler("admin@example.com");

        assertThatExceptionOfType(org.springframework.security.access.AccessDeniedException.class)
                .isThrownBy(() -> scheduler.adminRefresh(null, null, "someone-else@example.com"));
    }

    @Test
    void adminRefreshAllowsACallerInAdminEmails() {
        when(instrumentLookupRepository.findAll()).thenReturn(List.of(AAPL));
        when(priceHistoryRepository.mostRecentOnOrBefore(1L, TODAY)).thenReturn(Optional.empty());
        when(marketDataProvider.dailyCloses("AAPL", TODAY.minusDays(7), TODAY)).thenReturn(List.of());

        PriceRefreshScheduler scheduler = scheduler("Admin@Example.com");

        RefreshPricesResult result = scheduler.adminRefresh(null, null, "admin@example.com");

        assertThat(result.requested()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1); // empty provider result -> SKIPPED, not FAILED
    }

    @Test
    void refreshingTwoSymbolsOnlyTargetsThoseSymbols() {
        when(instrumentLookupRepository.findAll()).thenReturn(List.of(AAPL, MSFT, RELIANCE));
        when(priceHistoryRepository.mostRecentOnOrBefore(1L, TODAY)).thenReturn(Optional.empty());
        when(marketDataProvider.dailyCloses("AAPL", TODAY.minusDays(7), TODAY)).thenReturn(List.of());

        RefreshPricesResult result = scheduler("").refresh(List.of("AAPL"), null);

        assertThat(result.requested()).isEqualTo(1);
        assertThat(result.results()).extracting(SymbolRefreshResult::symbol).containsExactly("AAPL");
    }
}
