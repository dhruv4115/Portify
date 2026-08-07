package com.protify.portfolio.marketdata;

import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.enums.PriceSource;
import com.protify.portfolio.common.port.MarketDataProvider;
import com.protify.portfolio.common.port.PriceQuote;
import com.protify.portfolio.support.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D3-B1 — {@code PriceRefreshIT}: refreshing twice against a real MySQL (Testcontainers)
 * produces no duplicate {@code price_history} rows. The provider is a Mockito stub, never a
 * real HTTP call — "No test calls a real provider" applies to {@code *IT} classes too.
 */
class PriceRefreshIT extends AbstractIntegrationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-10T00:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 1, 10);

    private NamedParameterJdbcTemplate jdbc;
    private PriceHistoryRepository priceHistoryRepository;
    private InstrumentLookupRepository instrumentLookupRepository;
    private MarketDataProvider marketDataProvider;

    @BeforeEach
    void setUp() {
        jdbc = jdbcTemplate();
        priceHistoryRepository = new PriceHistoryRepository(jdbc);
        instrumentLookupRepository = new InstrumentLookupRepository(jdbc);
        marketDataProvider = Mockito.mock(MarketDataProvider.class);
    }

    @Test
    void refreshingTwiceProducesNoDuplicateRows() {
        long aaplId = instrumentLookupRepository.findAll().stream()
                .filter(i -> i.symbol().equals("AAPL"))
                .findFirst().orElseThrow().id();

        Mockito.when(marketDataProvider.dailyCloses(Mockito.eq("AAPL"), Mockito.any(), Mockito.any()))
                .thenReturn(List.of(new PriceQuote("AAPL", TODAY, new BigDecimal("199.5000"), CurrencyCode.USD, PriceSource.YAHOO)));

        PriceRefreshScheduler scheduler = new PriceRefreshScheduler(marketDataProvider, priceHistoryRepository,
                instrumentLookupRepository, CLOCK, "");

        scheduler.refresh(List.of("AAPL"), TODAY.minusDays(1));
        int countAfterFirst = countPriceHistoryRows(aaplId, TODAY);

        scheduler.refresh(List.of("AAPL"), TODAY.minusDays(1));
        int countAfterSecond = countPriceHistoryRows(aaplId, TODAY);

        assertThat(countAfterFirst).isEqualTo(1);
        assertThat(countAfterSecond).isEqualTo(1); // upsert on (instrument_id, price_date) — no duplicate
    }

    private int countPriceHistoryRows(long instrumentId, LocalDate date) {
        Integer count = jdbc.getJdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM price_history WHERE instrument_id = ? AND price_date = ?",
                Integer.class, instrumentId, java.sql.Date.valueOf(date));
        return count == null ? 0 : count;
    }
}
