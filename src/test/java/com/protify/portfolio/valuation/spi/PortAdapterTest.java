package com.protify.portfolio.valuation.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.enums.FxSource;
import com.protify.portfolio.common.enums.PriceSource;
import com.protify.portfolio.common.money.Money;
import com.protify.portfolio.common.port.FxQuote;
import com.protify.portfolio.fx.CachingFxRateService;
import com.protify.portfolio.fx.ConversionResult;
import com.protify.portfolio.fx.FxRateRepository;
import com.protify.portfolio.marketdata.CachingMarketDataService;
import com.protify.portfolio.marketdata.PriceHistoryRepository;
import com.protify.portfolio.marketdata.PriceResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The two {@code *Adapter} classes are the only core code allowed to import {@code marketdata}
 * and {@code fx} — see {@code ArchitectureTest#coreMustNotDependOnPlatform}. That exemption is
 * only defensible while they stay translation-only, so what these tests assert is exactly that:
 * the platform type goes in, the core type comes out, and no value or date is altered on the way
 * through. A decision appearing in either class would show up here as a test that needs more
 * than one stub to write.
 */
@ExtendWith(MockitoExtension.class)
class PortAdapterTest {

    private static final LocalDate DATE = LocalDate.parse("2026-06-01");

    @Mock
    private CachingMarketDataService marketDataService;
    @Mock
    private PriceHistoryRepository priceHistoryRepository;
    @Mock
    private CachingFxRateService fxRateService;
    @Mock
    private FxRateRepository fxRateRepository;

    @Test
    void shouldTranslateAPriceResultToAPricePointPreservingTheAsOfDate() {
        LocalDate staleDate = DATE.minusDays(3);
        when(marketDataService.priceFor(100L, DATE)).thenReturn(Optional.of(
                new PriceResult(new BigDecimal("150.0000"), CurrencyCode.USD, staleDate, PriceSource.SEED)));

        Optional<PricePoint> result = new MarketDataPriceLookupAdapter(marketDataService, priceHistoryRepository)
                .priceOn(100L, DATE);

        assertThat(result).isPresent();
        assertThat(result.get().price()).isEqualByComparingTo("150.0000");
        assertThat(result.get().currency()).isEqualTo(CurrencyCode.USD);
        // The stale date survives translation — dropping it would hide staleness (TEST_PLAN §4.3).
        assertThat(result.get().asOf()).isEqualTo(staleDate);
    }

    @Test
    void shouldReturnEmptyWhenNoPriceExistsAnywhere() {
        when(marketDataService.priceFor(100L, DATE)).thenReturn(Optional.empty());

        assertThat(new MarketDataPriceLookupAdapter(marketDataService, priceHistoryRepository)
                .priceOn(100L, DATE)).isEmpty();
    }

    @Test
    void shouldPassThePriceSeriesThroughUnchanged() {
        NavigableMap<LocalDate, BigDecimal> series = new TreeMap<>();
        series.put(DATE, new BigDecimal("150.0000"));
        when(priceHistoryRepository.findRange(Set.of(100L), DATE)).thenReturn(Map.of(100L, series));

        Map<Long, NavigableMap<LocalDate, BigDecimal>> result =
                new MarketDataPriceLookupAdapter(marketDataService, priceHistoryRepository)
                        .seriesUpTo(Set.of(100L), DATE);

        assertThat(result).containsOnlyKeys(100L);
        assertThat(result.get(100L)).containsExactlyEntriesOf(series);
    }

    @Test
    void shouldTranslateAConversionResultToConvertedMoney() {
        LocalDate rateDate = DATE.minusDays(1);
        when(fxRateService.convert(new Money(new BigDecimal("100.0000"), CurrencyCode.USD), CurrencyCode.INR, DATE))
                .thenReturn(Optional.of(new ConversionResult(new BigDecimal("8500.0000"), CurrencyCode.INR, rateDate)));

        Optional<ConvertedMoney> result = new FxRateConversionAdapter(fxRateService, fxRateRepository)
                .convert(new Money(new BigDecimal("100.0000"), CurrencyCode.USD), CurrencyCode.INR, DATE);

        assertThat(result).isPresent();
        assertThat(result.get().amount()).isEqualByComparingTo("8500.0000");
        assertThat(result.get().currency()).isEqualTo(CurrencyCode.INR);
        assertThat(result.get().asOf()).isEqualTo(rateDate);
    }

    @Test
    void shouldUnwrapTheBareRateFromAnFxQuote() {
        when(fxRateService.rate(CurrencyCode.USD, CurrencyCode.INR, DATE)).thenReturn(
                Optional.of(new FxQuote(CurrencyCode.USD, CurrencyCode.INR, DATE,
                        new BigDecimal("85.00000000"), FxSource.FRANKFURTER)));

        Optional<BigDecimal> rate = new FxRateConversionAdapter(fxRateService, fxRateRepository)
                .rate(CurrencyCode.USD, CurrencyCode.INR, DATE);

        assertThat(rate).isPresent();
        assertThat(rate.get()).isEqualByComparingTo("85.00000000");
    }

    @Test
    void shouldReturnEmptyWhenNoRateCanBeResolved() {
        when(fxRateService.rate(CurrencyCode.USD, CurrencyCode.INR, DATE)).thenReturn(Optional.empty());

        assertThat(new FxRateConversionAdapter(fxRateService, fxRateRepository)
                .rate(CurrencyCode.USD, CurrencyCode.INR, DATE)).isEmpty();
    }

    @Test
    void shouldPassTheUsdPivotRateTableThroughUnchanged() {
        NavigableMap<LocalDate, Map<CurrencyCode, BigDecimal>> table = new TreeMap<>();
        table.put(DATE, Map.of(CurrencyCode.INR, new BigDecimal("85"), CurrencyCode.GBP, new BigDecimal("0.79")));
        when(fxRateRepository.findRange(DATE)).thenReturn(table);

        NavigableMap<LocalDate, Map<CurrencyCode, BigDecimal>> result =
                new FxRateConversionAdapter(fxRateService, fxRateRepository).ratesUpTo(DATE);

        assertThat(result).containsOnlyKeys(DATE);
        assertThat(result.get(DATE)).containsOnlyKeys(List.of(CurrencyCode.INR, CurrencyCode.GBP).toArray(CurrencyCode[]::new));
    }
}
