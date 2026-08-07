package com.protify.portfolio.fx;

import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.enums.FxSource;
import com.protify.portfolio.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** D2-B3 — {@code FxRateRepositoryIT}: upsert is idempotent under repeat, against a real MySQL
 * (Testcontainers). */
class FxRateRepositoryIT extends AbstractIntegrationTest {

    private FxRateRepository repository;

    @BeforeEach
    void setUp() {
        NamedParameterJdbcTemplate jdbc = jdbcTemplate();
        repository = new FxRateRepository(jdbc);
    }

    @Test
    void upsertIsIdempotentUnderRepeat() {
        LocalDate date = LocalDate.of(2020, 1, 15); // clear of the two-year seed window either way

        repository.upsert(CurrencyCode.USD, CurrencyCode.INR, date, new BigDecimal("74.10000000"), FxSource.FRANKFURTER);
        repository.upsert(CurrencyCode.USD, CurrencyCode.INR, date, new BigDecimal("74.10000000"), FxSource.FRANKFURTER);

        Optional<FxRateRow> row = repository.mostRecentOnOrBefore(CurrencyCode.USD, CurrencyCode.INR, date);
        assertThat(row).isPresent();
        assertThat(row.get().rate()).isEqualByComparingTo("74.10000000");
    }

    @Test
    void upsertOverwritesTheRateOnConflict() {
        LocalDate date = LocalDate.of(2020, 2, 1);

        repository.upsert(CurrencyCode.USD, CurrencyCode.GBP, date, new BigDecimal("0.75000000"), FxSource.FRANKFURTER);
        repository.upsert(CurrencyCode.USD, CurrencyCode.GBP, date, new BigDecimal("0.76000000"), FxSource.FRANKFURTER);

        Optional<FxRateRow> row = repository.mostRecentOnOrBefore(CurrencyCode.USD, CurrencyCode.GBP, date);
        assertThat(row).isPresent();
        assertThat(row.get().rate()).isEqualByComparingTo("0.76000000");
    }

    @Test
    void mostRecentOnOrBeforeNeverReturnsAFutureRate() {
        LocalDate past = LocalDate.of(2020, 3, 1);
        LocalDate future = LocalDate.of(2020, 3, 10);

        repository.upsert(CurrencyCode.USD, CurrencyCode.EUR, past, new BigDecimal("0.90000000"), FxSource.FRANKFURTER);
        repository.upsert(CurrencyCode.USD, CurrencyCode.EUR, future, new BigDecimal("0.95000000"), FxSource.FRANKFURTER);

        Optional<FxRateRow> row = repository.mostRecentOnOrBefore(CurrencyCode.USD, CurrencyCode.EUR, LocalDate.of(2020, 3, 5));

        assertThat(row).isPresent();
        assertThat(row.get().rateDate()).isEqualTo(past);
    }

    @Test
    void findExactOnlyMatchesTheExactDate() {
        LocalDate date = LocalDate.of(2020, 4, 1);
        repository.upsert(CurrencyCode.USD, CurrencyCode.EUR, date.minusDays(1), new BigDecimal("0.88000000"), FxSource.FRANKFURTER);

        assertThat(repository.findExact(CurrencyCode.USD, CurrencyCode.EUR, date)).isEmpty();

        repository.upsert(CurrencyCode.USD, CurrencyCode.EUR, date, new BigDecimal("0.89000000"), FxSource.FRANKFURTER);

        Optional<FxRateRow> exact = repository.findExact(CurrencyCode.USD, CurrencyCode.EUR, date);
        assertThat(exact).isPresent();
        assertThat(exact.get().rate()).isEqualByComparingTo("0.89000000");
    }
}
