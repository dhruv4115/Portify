package com.protify.portfolio.marketdata;

import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.enums.PriceSource;
import com.protify.portfolio.common.port.MarketDataProvider;
import com.protify.portfolio.common.port.PriceQuote;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D2-B2 — {@code CachingMarketDataServiceTest}: unit test, every collaborator mocked. No test
 * here calls a real provider or a real database.
 */
@ExtendWith(MockitoExtension.class)
class CachingMarketDataServiceTest {

    @Mock
    private MarketDataProvider marketDataProvider;
    @Mock
    private PriceHistoryRepository priceHistoryRepository;
    @Mock
    private InstrumentLookupRepository instrumentLookupRepository;

    private static final InstrumentLookupRepository.InstrumentRef AAPL =
            new InstrumentLookupRepository.InstrumentRef(1L, "AAPL", CurrencyCode.USD);

    private CachingMarketDataService service() {
        return new CachingMarketDataService(marketDataProvider, priceHistoryRepository, instrumentLookupRepository);
    }

    @Test
    void cacheHitDoesNotCallTheProvider() {
        LocalDate date = LocalDate.of(2026, 1, 5);
        when(instrumentLookupRepository.findById(1L)).thenReturn(Optional.of(AAPL));
        when(marketDataProvider.dailyCloses("AAPL", date, date))
                .thenReturn(List.of(new PriceQuote("AAPL", date, new BigDecimal("190.5000"), CurrencyCode.USD, PriceSource.YAHOO)));
        when(marketDataProvider.sourceName()).thenReturn("YAHOO");

        CachingMarketDataService service = service();

        Optional<PriceResult> first = service.priceFor(1L, date);
        Optional<PriceResult> second = service.priceFor(1L, date);

        assertThat(first).isPresent();
        assertThat(second).isPresent();
        assertThat(second.get()).isEqualTo(first.get());
        verify(marketDataProvider, times(1)).dailyCloses("AAPL", date, date);
    }

    @Test
    void providerFailureFallsBackToPriceHistory() {
        LocalDate date = LocalDate.of(2026, 1, 5);
        LocalDate historicalDate = LocalDate.of(2026, 1, 2);
        when(instrumentLookupRepository.findById(1L)).thenReturn(Optional.of(AAPL));
        when(marketDataProvider.dailyCloses("AAPL", date, date)).thenThrow(new RuntimeException("upstream down"));
        when(priceHistoryRepository.mostRecentOnOrBefore(1L, date))
                .thenReturn(Optional.of(new PriceHistoryRow(1L, historicalDate, new BigDecimal("188.0000"), PriceSource.YAHOO)));

        Optional<PriceResult> result = service().priceFor(1L, date);

        assertThat(result).isPresent();
        assertThat(result.get().price()).isEqualByComparingTo("188.0000");
        // priceAsOf reflects the real date of whatever was served, not the requested date
        assertThat(result.get().asOf()).isEqualTo(historicalDate);
    }

    @Test
    void emptyProviderResultFallsBackToSeedRowInPriceHistory() {
        LocalDate date = LocalDate.of(2026, 1, 5);
        LocalDate seedDate = LocalDate.of(2025, 12, 31);
        when(instrumentLookupRepository.findById(1L)).thenReturn(Optional.of(AAPL));
        when(marketDataProvider.dailyCloses("AAPL", date, date)).thenReturn(List.of());
        when(priceHistoryRepository.mostRecentOnOrBefore(1L, date))
                .thenReturn(Optional.of(new PriceHistoryRow(1L, seedDate, new BigDecimal("180.0000"), PriceSource.SEED)));

        Optional<PriceResult> result = service().priceFor(1L, date);

        assertThat(result).isPresent();
        assertThat(result.get().source()).isEqualTo(PriceSource.SEED);
        assertThat(result.get().asOf()).isEqualTo(seedDate);
    }

    @Test
    void returnsEmptyWhenNeitherProviderNorHistoryHasAnything() {
        LocalDate date = LocalDate.of(2026, 1, 5);
        when(instrumentLookupRepository.findById(1L)).thenReturn(Optional.of(AAPL));
        when(marketDataProvider.dailyCloses("AAPL", date, date)).thenReturn(List.of());
        when(priceHistoryRepository.mostRecentOnOrBefore(1L, date)).thenReturn(Optional.empty());

        Optional<PriceResult> result = service().priceFor(1L, date);

        assertThat(result).isEmpty();
    }

    @Test
    void secondCallWithinTtlCallsTheProviderExactlyOnce() {
        LocalDate date = LocalDate.of(2026, 1, 5);
        when(instrumentLookupRepository.findById(1L)).thenReturn(Optional.of(AAPL));
        when(marketDataProvider.dailyCloses("AAPL", date, date))
                .thenReturn(List.of(new PriceQuote("AAPL", date, new BigDecimal("190.5000"), CurrencyCode.USD, PriceSource.YAHOO)));
        when(marketDataProvider.sourceName()).thenReturn("YAHOO");

        CachingMarketDataService service = service();
        service.priceFor(1L, date);
        service.priceFor(1L, date);
        service.priceFor(1L, date);

        verify(marketDataProvider, times(1)).dailyCloses("AAPL", date, date);
        verify(instrumentLookupRepository, never()).findById(2L);
    }

    @Test
    void neverThrowsWhenProviderThrowsAndHistoryIsAlsoEmpty() {
        LocalDate date = LocalDate.of(2026, 1, 5);
        when(instrumentLookupRepository.findById(1L)).thenReturn(Optional.of(AAPL));
        when(marketDataProvider.dailyCloses("AAPL", date, date)).thenThrow(new RuntimeException("timeout"));
        when(priceHistoryRepository.mostRecentOnOrBefore(1L, date)).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatCode(() -> service().priceFor(1L, date))
                .doesNotThrowAnyException();
    }
}
