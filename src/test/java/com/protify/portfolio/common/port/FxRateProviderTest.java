package com.protify.portfolio.common.port;

import static org.assertj.core.api.Assertions.assertThat;

import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.enums.FxSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Compile-only contract, same rationale as {@link MarketDataProviderTest}.
 */
class FxRateProviderTest {

    private final FxRateProvider provider = new StubFxRateProvider();

    @Test
    void shouldReturnRateFromStubImplementation() {
        Optional<FxQuote> quote = provider.rate(CurrencyCode.USD, CurrencyCode.INR, LocalDate.of(2026, 1, 2));

        assertThat(quote).isPresent();
        assertThat(quote.get().from()).isEqualTo(CurrencyCode.USD);
        assertThat(quote.get().to()).isEqualTo(CurrencyCode.INR);
        assertThat(quote.get().source()).isEqualTo(FxSource.SEED);
    }

    @Test
    void shouldReturnRatesForBaseCurrencyFromStubImplementation() {
        Map<CurrencyCode, BigDecimal> rates = provider.ratesFor(CurrencyCode.USD, LocalDate.of(2026, 1, 2));

        assertThat(rates).containsKey(CurrencyCode.INR);
    }

    @Test
    void shouldExposeSourceName() {
        assertThat(provider.sourceName()).isEqualTo("stub");
    }

    private static final class StubFxRateProvider implements FxRateProvider {

        @Override
        public Optional<FxQuote> rate(CurrencyCode from, CurrencyCode to, LocalDate on) {
            return Optional.of(new FxQuote(from, to, on, new BigDecimal("87.00000000"), FxSource.SEED));
        }

        @Override
        public Map<CurrencyCode, BigDecimal> ratesFor(CurrencyCode base, LocalDate on) {
            return Map.of(CurrencyCode.INR, new BigDecimal("87.00000000"));
        }

        @Override
        public String sourceName() {
            return "stub";
        }
    }
}
