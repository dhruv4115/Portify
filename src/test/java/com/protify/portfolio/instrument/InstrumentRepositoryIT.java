package com.protify.portfolio.instrument;

import static org.assertj.core.api.Assertions.assertThat;

import com.protify.portfolio.common.enums.AssetType;
import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.support.AbstractIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Runs against the real V2 seed data (19 instruments across USD/INR/GBP/EUR). */
class InstrumentRepositoryIT extends AbstractIntegrationTest {

    private InstrumentRepository repository;
    private NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = jdbcTemplate();
        repository = new InstrumentRepository(jdbc);
    }

    @Test
    void searchShouldRankSymbolPrefixAboveNameSubstring() {
        List<Instrument> results = repository.search("rel", null, null, 10);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).symbol()).isEqualTo("RELIANCE");
    }

    @Test
    void findBySymbolShouldBeCaseInsensitiveAndTrimmed() {
        Optional<Instrument> found = repository.findBySymbol(" aapl ");

        assertThat(found).isPresent();
        assertThat(found.get().symbol()).isEqualTo("AAPL");
    }

    @Test
    void findBySymbolShouldReturnEmptyForUnknownSymbol() {
        Optional<Instrument> found = repository.findBySymbol("NOPE-DOES-NOT-EXIST");

        assertThat(found).isEmpty();
    }

    @Test
    void searchShouldRespectLimit() {
        List<Instrument> results = repository.search(null, null, null, 2);

        assertThat(results).hasSize(2);
    }

    @Test
    void searchShouldCapAtFiftyEvenWhenMoreMatchAndMoreIsRequested() {
        // 19 seed rows aren't enough to prove the cap by themselves — insert enough extras that
        // an unbounded query would return more than 50. Cleaned up at the end: SeedDataIT shares
        // this same Testcontainers instance and asserts an exact row count.
        try {
            for (int i = 0; i < 40; i++) {
                jdbc.update("""
                        INSERT INTO instrument (symbol, name, asset_type, currency)
                        VALUES (:symbol, :name, 'STOCK', 'USD')
                        """, Map.of("symbol", "ZZZTEST" + i, "name", "Test Instrument " + i));
            }

            List<Instrument> results = repository.search(null, null, null, 100);

            assertThat(results).hasSizeLessThanOrEqualTo(50);
        } finally {
            jdbc.update("DELETE FROM instrument WHERE symbol LIKE 'ZZZTEST%'", Map.of());
        }
    }

    @Test
    void searchShouldComposeAssetTypeAndCurrencyFilters() {
        List<Instrument> results = repository.search(null, AssetType.STOCK, CurrencyCode.INR, 50);

        assertThat(results).isNotEmpty();
        assertThat(results).allSatisfy(instrument -> {
            assertThat(instrument.assetType()).isEqualTo(AssetType.STOCK);
            assertThat(instrument.currency()).isEqualTo(CurrencyCode.INR);
        });
    }
}
