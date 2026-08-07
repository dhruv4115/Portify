package com.protify.portfolio.fx;

import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.enums.FxSource;
import com.protify.portfolio.common.port.FxRateProvider;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D3-B3 — {@code FxRefreshSchedulerTest}: provider and repository mocked; no test calls a real
 * provider or database.
 */
@ExtendWith(MockitoExtension.class)
class FxRefreshSchedulerTest {

    @Mock
    private FxRateProvider fxRateProvider;
    @Mock
    private FxRateRepository fxRateRepository;

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-10T00:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 1, 10);

    private FxRefreshScheduler scheduler() {
        return new FxRefreshScheduler(fxRateProvider, fxRateRepository, CLOCK);
    }

    private static Map<CurrencyCode, BigDecimal> allRates() {
        Map<CurrencyCode, BigDecimal> rates = new EnumMap<>(CurrencyCode.class);
        rates.put(CurrencyCode.USD, BigDecimal.ONE);
        rates.put(CurrencyCode.EUR, new BigDecimal("0.9100"));
        rates.put(CurrencyCode.GBP, new BigDecimal("0.7900"));
        rates.put(CurrencyCode.INR, new BigDecimal("83.0000"));
        return rates;
    }

    @Test
    void anUnseenDateIsFetchedAndStored() {
        LocalDate unseen = LocalDate.of(2026, 1, 8);
        when(fxRateRepository.findExact(CurrencyCode.USD, CurrencyCode.EUR, unseen)).thenReturn(Optional.empty());
        when(fxRateRepository.findExact(CurrencyCode.USD, CurrencyCode.GBP, unseen)).thenReturn(Optional.empty());
        when(fxRateRepository.findExact(CurrencyCode.USD, CurrencyCode.INR, unseen)).thenReturn(Optional.empty());
        when(fxRateProvider.ratesFor(CurrencyCode.USD, unseen)).thenReturn(allRates());

        scheduler().ensureRatesFor(unseen);

        verify(fxRateRepository).upsert(CurrencyCode.USD, CurrencyCode.EUR, unseen, new BigDecimal("0.91000000"), FxSource.FRANKFURTER);
        verify(fxRateRepository).upsert(CurrencyCode.USD, CurrencyCode.GBP, unseen, new BigDecimal("0.79000000"), FxSource.FRANKFURTER);
        verify(fxRateRepository).upsert(CurrencyCode.USD, CurrencyCode.INR, unseen, new BigDecimal("83.00000000"), FxSource.FRANKFURTER);
    }

    @Test
    void theSameDateTwiceCallsTheProviderOnlyOnce() {
        LocalDate date = LocalDate.of(2026, 1, 8);
        when(fxRateRepository.findExact(CurrencyCode.USD, CurrencyCode.EUR, date))
                .thenReturn(Optional.empty(), Optional.of(new FxRateRow(CurrencyCode.USD, CurrencyCode.EUR, date, BigDecimal.ONE, FxSource.FRANKFURTER)));
        when(fxRateRepository.findExact(CurrencyCode.USD, CurrencyCode.GBP, date))
                .thenReturn(Optional.empty(), Optional.of(new FxRateRow(CurrencyCode.USD, CurrencyCode.GBP, date, BigDecimal.ONE, FxSource.FRANKFURTER)));
        when(fxRateRepository.findExact(CurrencyCode.USD, CurrencyCode.INR, date))
                .thenReturn(Optional.empty(), Optional.of(new FxRateRow(CurrencyCode.USD, CurrencyCode.INR, date, BigDecimal.ONE, FxSource.FRANKFURTER)));
        when(fxRateProvider.ratesFor(CurrencyCode.USD, date)).thenReturn(allRates());

        FxRefreshScheduler scheduler = scheduler();
        scheduler.ensureRatesFor(date); // first call: nothing stored yet -> fetches
        scheduler.ensureRatesFor(date); // second call: findExact now reports present -> must not re-fetch

        verify(fxRateProvider, times(1)).ratesFor(CurrencyCode.USD, date);
    }

    @Test
    void aProviderFailureDoesNotThrowAndLeavesExistingDataAsTheFallback() {
        LocalDate date = LocalDate.of(2026, 1, 8);
        when(fxRateRepository.findExact(CurrencyCode.USD, CurrencyCode.EUR, date)).thenReturn(Optional.empty());
        when(fxRateRepository.findExact(CurrencyCode.USD, CurrencyCode.GBP, date)).thenReturn(Optional.empty());
        when(fxRateRepository.findExact(CurrencyCode.USD, CurrencyCode.INR, date)).thenReturn(Optional.empty());
        when(fxRateProvider.ratesFor(CurrencyCode.USD, date)).thenThrow(new RuntimeException("provider down"));

        org.assertj.core.api.Assertions.assertThatCode(() -> scheduler().ensureRatesFor(date)).doesNotThrowAnyException();

        verify(fxRateRepository, never()).upsert(org.mockito.ArgumentMatchers.eq(CurrencyCode.USD),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(date),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        // The honest, stale fallback itself is CachingFxRateService's job (FxRateServiceTest
        // covers "providerDownUsesLastGoodAndReportsTheRealDate"); this class's contract is
        // simply to never throw and never corrupt fx_rate on a failed fetch.
    }

    @Test
    void neverFetchesOrStoresAFutureDate() {
        LocalDate future = TODAY.plusDays(1);

        scheduler().ensureRatesFor(future);

        verify(fxRateProvider, never()).ratesFor(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(fxRateRepository, never()).upsert(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
