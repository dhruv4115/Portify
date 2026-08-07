package com.protify.portfolio.fx;

import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.enums.FxSource;
import com.protify.portfolio.common.money.Money;
import com.protify.portfolio.common.port.FxRateProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * D2-B3 — {@code FxRateServiceTest}: unit test, provider and repository mocked. No test here
 * calls a real provider or database, and no rate is ever hard-coded as production config —
 * these are fixture values for the test only.
 */
@ExtendWith(MockitoExtension.class)
class FxRateServiceTest {

    @Mock
    private FxRateProvider fxRateProvider;
    @Mock
    private FxRateRepository fxRateRepository;

    private static final LocalDate DATE = LocalDate.of(2026, 1, 5);

    private CachingFxRateService service() {
        return new CachingFxRateService(fxRateProvider, fxRateRepository);
    }

    @Test
    void conversionIsCorrectForAStoredPivotPair() {
        when(fxRateProvider.ratesFor(CurrencyCode.USD, DATE)).thenReturn(Map.of());
        when(fxRateRepository.mostRecentOnOrBefore(CurrencyCode.USD, CurrencyCode.INR, DATE))
                .thenReturn(Optional.of(new FxRateRow(CurrencyCode.USD, CurrencyCode.INR, DATE,
                        new BigDecimal("83.00000000"), FxSource.FRANKFURTER)));

        Optional<com.protify.portfolio.common.port.FxQuote> quote = service().rate(CurrencyCode.USD, CurrencyCode.INR, DATE);

        assertThat(quote).isPresent();
        assertThat(quote.get().rate()).isEqualByComparingTo("83.00000000");
        assertThat(quote.get().date()).isEqualTo(DATE);
    }

    @Test
    void convertMultipliesByTheResolvedRate() {
        when(fxRateProvider.ratesFor(CurrencyCode.USD, DATE)).thenReturn(Map.of());
        when(fxRateRepository.mostRecentOnOrBefore(CurrencyCode.USD, CurrencyCode.INR, DATE))
                .thenReturn(Optional.of(new FxRateRow(CurrencyCode.USD, CurrencyCode.INR, DATE,
                        new BigDecimal("83.00000000"), FxSource.FRANKFURTER)));

        Optional<ConversionResult> result = service()
                .convert(new Money(new BigDecimal("100.00"), CurrencyCode.USD), CurrencyCode.INR, DATE);

        assertThat(result).isPresent();
        assertThat(result.get().amount()).isEqualByComparingTo("8300.0000");
        assertThat(result.get().currency()).isEqualTo(CurrencyCode.INR);
    }

    @Test
    void usdToInrToUsdRoundTripsWithinTolerance() {
        when(fxRateProvider.ratesFor(CurrencyCode.USD, DATE)).thenReturn(Map.of());
        when(fxRateRepository.mostRecentOnOrBefore(CurrencyCode.USD, CurrencyCode.INR, DATE))
                .thenReturn(Optional.of(new FxRateRow(CurrencyCode.USD, CurrencyCode.INR, DATE,
                        new BigDecimal("83.12345678"), FxSource.FRANKFURTER)));

        CachingFxRateService service = service();
        BigDecimal usdToInr = service.rate(CurrencyCode.USD, CurrencyCode.INR, DATE).orElseThrow().rate();
        BigDecimal inrToUsd = service.rate(CurrencyCode.INR, CurrencyCode.USD, DATE).orElseThrow().rate();

        BigDecimal roundTrip = usdToInr.multiply(inrToUsd);
        assertThat(roundTrip.subtract(BigDecimal.ONE).abs()).isLessThan(new BigDecimal("0.0001"));
    }

    @Test
    void providerDownUsesLastGoodAndReportsTheRealDate() {
        LocalDate staleDate = DATE.minusDays(4);
        when(fxRateProvider.ratesFor(CurrencyCode.USD, DATE)).thenThrow(new RuntimeException("provider down"));
        when(fxRateRepository.mostRecentOnOrBefore(CurrencyCode.USD, CurrencyCode.GBP, DATE))
                .thenReturn(Optional.of(new FxRateRow(CurrencyCode.USD, CurrencyCode.GBP, staleDate,
                        new BigDecimal("0.79000000"), FxSource.FRANKFURTER)));

        Optional<com.protify.portfolio.common.port.FxQuote> quote = service().rate(CurrencyCode.USD, CurrencyCode.GBP, DATE);

        assertThat(quote).isPresent();
        assertThat(quote.get().date()).isEqualTo(staleDate);
        assertThat(quote.get().rate()).isEqualByComparingTo("0.79000000");
    }

    @Test
    void missingDateFallsBackToMostRecentPrior() {
        LocalDate priorDate = LocalDate.of(2025, 12, 30);
        when(fxRateProvider.ratesFor(CurrencyCode.USD, DATE)).thenReturn(Map.of());
        when(fxRateRepository.mostRecentOnOrBefore(CurrencyCode.USD, CurrencyCode.EUR, DATE))
                .thenReturn(Optional.of(new FxRateRow(CurrencyCode.USD, CurrencyCode.EUR, priorDate,
                        new BigDecimal("0.91000000"), FxSource.SEED)));

        Optional<com.protify.portfolio.common.port.FxQuote> quote = service().rate(CurrencyCode.USD, CurrencyCode.EUR, DATE);

        assertThat(quote).isPresent();
        assertThat(quote.get().date()).isEqualTo(priorDate);
        assertThat(quote.get().source()).isEqualTo(FxSource.SEED);
    }

    @Test
    void crossRateBetweenTwoNonUsdCurrenciesIsDerivedFromThePivot() {
        when(fxRateProvider.ratesFor(CurrencyCode.USD, DATE)).thenReturn(Map.of());
        when(fxRateRepository.mostRecentOnOrBefore(CurrencyCode.USD, CurrencyCode.GBP, DATE))
                .thenReturn(Optional.of(new FxRateRow(CurrencyCode.USD, CurrencyCode.GBP, DATE,
                        new BigDecimal("0.80000000"), FxSource.FRANKFURTER)));
        when(fxRateRepository.mostRecentOnOrBefore(CurrencyCode.USD, CurrencyCode.INR, DATE))
                .thenReturn(Optional.of(new FxRateRow(CurrencyCode.USD, CurrencyCode.INR, DATE,
                        new BigDecimal("84.00000000"), FxSource.FRANKFURTER)));

        // GBP -> INR = rate(USD->INR) / rate(USD->GBP) = 84 / 0.8 = 105
        Optional<com.protify.portfolio.common.port.FxQuote> quote = service().rate(CurrencyCode.GBP, CurrencyCode.INR, DATE);

        assertThat(quote).isPresent();
        assertThat(quote.get().rate()).isEqualByComparingTo("105.00000000");
    }

    @Test
    void returnsEmptyWhenNoPivotDataExistsAtAll() {
        when(fxRateProvider.ratesFor(CurrencyCode.USD, DATE)).thenReturn(Map.of());
        when(fxRateRepository.mostRecentOnOrBefore(CurrencyCode.USD, CurrencyCode.INR, DATE)).thenReturn(Optional.empty());

        assertThat(service().rate(CurrencyCode.USD, CurrencyCode.INR, DATE)).isEmpty();
    }

    @Test
    void sameCurrencyIsAlwaysOneWithNoProviderCall() {
        Optional<com.protify.portfolio.common.port.FxQuote> quote = service().rate(CurrencyCode.USD, CurrencyCode.USD, DATE);

        assertThat(quote).isPresent();
        assertThat(quote.get().rate()).isEqualByComparingTo("1.00000000");
    }

    @Test
    void secondRequestForAnAlreadyFetchedExactDateDoesNotCallTheProviderAgain() {
        // D3-B3: fx_rate already has every non-USD pivot row for this exact date (as if an
        // earlier call, the scheduler, or an on-demand backfill already fetched it) — a fresh
        // CachingFxRateService instance (empty cache) must still not call the provider.
        for (CurrencyCode c : CurrencyCode.values()) {
            if (c != CurrencyCode.USD) {
                when(fxRateRepository.findExact(CurrencyCode.USD, c, DATE))
                        .thenReturn(Optional.of(new FxRateRow(CurrencyCode.USD, c, DATE,
                                new BigDecimal("1.23000000"), FxSource.FRANKFURTER)));
            }
        }
        when(fxRateRepository.mostRecentOnOrBefore(CurrencyCode.USD, CurrencyCode.INR, DATE))
                .thenReturn(Optional.of(new FxRateRow(CurrencyCode.USD, CurrencyCode.INR, DATE,
                        new BigDecimal("83.00000000"), FxSource.FRANKFURTER)));

        Optional<com.protify.portfolio.common.port.FxQuote> quote = service().rate(CurrencyCode.USD, CurrencyCode.INR, DATE);

        assertThat(quote).isPresent();
        org.mockito.Mockito.verify(fxRateProvider, org.mockito.Mockito.never()).ratesFor(CurrencyCode.USD, DATE);
    }
}
