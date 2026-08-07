package com.protify.portfolio.common.port;

import static org.assertj.core.api.Assertions.assertThat;

import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.enums.PriceSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Compile-only contract: a stub adapter satisfies {@link MarketDataProvider} with nothing but
 * types from {@code common}, proving {@code portfolio-core} can depend on the port without ever
 * seeing an HTTP client (ADR-0008).
 */
class MarketDataProviderTest {

    private final MarketDataProvider provider = new StubMarketDataProvider();

    @Test
    void shouldReturnLatestPriceFromStubImplementation() {
        Optional<PriceQuote> quote = provider.latestPrice("AAPL");

        assertThat(quote).isPresent();
        assertThat(quote.get().symbol()).isEqualTo("AAPL");
        assertThat(quote.get().currency()).isEqualTo(CurrencyCode.USD);
        assertThat(quote.get().source()).isEqualTo(PriceSource.SEED);
    }

    @Test
    void shouldReturnDailyClosesFromStubImplementation() {
        List<PriceQuote> closes = provider.dailyCloses("AAPL", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2));

        assertThat(closes).hasSize(1);
    }

    @Test
    void shouldExposeSourceName() {
        assertThat(provider.sourceName()).isEqualTo("stub");
    }

    private static final class StubMarketDataProvider implements MarketDataProvider {

        @Override
        public Optional<PriceQuote> latestPrice(String symbol) {
            return Optional.of(new PriceQuote(symbol, LocalDate.of(2026, 1, 2),
                    new BigDecimal("100.0000"), CurrencyCode.USD, PriceSource.SEED));
        }

        @Override
        public List<PriceQuote> dailyCloses(String symbol, LocalDate from, LocalDate to) {
            return List.of(new PriceQuote(symbol, to, new BigDecimal("100.0000"), CurrencyCode.USD, PriceSource.SEED));
        }

        @Override
        public String sourceName() {
            return "stub";
        }
    }
}
