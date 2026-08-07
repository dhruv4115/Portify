package com.protify.portfolio.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.protify.portfolio.common.enums.CurrencyCode;
import java.math.BigDecimal;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class DomainExceptionTest {

    @Nested
    class NotFound {

        @Test
        void shouldBuildSlugFromResourceName() {
            NotFoundException ex = new NotFoundException("portfolio", 7);

            assertThat(ex.problemType()).isEqualTo("/errors/portfolio-not-found");
            assertThat(ex.status()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(ex.resource()).isEqualTo("portfolio");
        }

        @Test
        void shouldBuildDifferentSlugForDifferentResource() {
            NotFoundException ex = new NotFoundException("instrument", "AAPL");

            assertThat(ex.problemType()).isEqualTo("/errors/instrument-not-found");
            assertThat(ex.getMessage()).contains("instrument", "AAPL");
        }
    }

    @Nested
    class Validation {

        @Test
        void shouldUseDefaultSlugWhenNoneGiven() {
            ValidationException ex = new ValidationException("quantity must be positive");

            assertThat(ex.problemType()).isEqualTo("/errors/validation-failed");
            assertThat(ex.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void shouldUseExplicitSlugWhenGiven() {
            ValidationException ex = new ValidationException("invalid-date-range", "from must not exceed to");

            assertThat(ex.problemType()).isEqualTo("/errors/invalid-date-range");
        }
    }

    @Nested
    class InsufficientQuantity {

        @Test
        void shouldCarrySymbolRequestedAndHeld() {
            InsufficientQuantityException ex = new InsufficientQuantityException(
                    "AAPL", new BigDecimal("50"), new BigDecimal("20"));

            assertThat(ex.symbol()).isEqualTo("AAPL");
            assertThat(ex.requested()).isEqualByComparingTo("50");
            assertThat(ex.held()).isEqualByComparingTo("20");
            assertThat(ex.problemType()).isEqualTo("/errors/insufficient-quantity");
            assertThat(ex.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test
        void shouldProduceASpecificHumanReadableMessage() {
            InsufficientQuantityException ex = new InsufficientQuantityException(
                    "AAPL", new BigDecimal("50.000000"), new BigDecimal("20.000000"));

            assertThat(ex.getMessage()).isEqualTo("Cannot sell 50 of AAPL; holding is 20.");
        }
    }

    @Nested
    class CurrencyMismatch {

        @Test
        void shouldCarryExpectedAndActualCurrency() {
            CurrencyMismatchException ex = new CurrencyMismatchException(CurrencyCode.USD, CurrencyCode.INR);

            assertThat(ex.expected()).isEqualTo(CurrencyCode.USD);
            assertThat(ex.actual()).isEqualTo(CurrencyCode.INR);
            assertThat(ex.problemType()).isEqualTo("/errors/currency-mismatch");
            assertThat(ex.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Nested
    class Upstream {

        @Test
        void shouldMapToBadGateway() {
            UpstreamException ex = new UpstreamException("YAHOO", "no cached or seeded price available");

            assertThat(ex.provider()).isEqualTo("YAHOO");
            assertThat(ex.problemType()).isEqualTo("/errors/upstream-unavailable");
            assertThat(ex.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
        }

        @Test
        void shouldPreserveCauseWhenWrappingAnUnderlyingFailure() {
            RuntimeException cause = new RuntimeException("connection reset");

            UpstreamException ex = new UpstreamException("FRANKFURTER", "fx provider failed", cause);

            assertThat(ex.getCause()).isSameAs(cause);
        }
    }

    @Nested
    class Hierarchy {

        @Test
        void everySubclassShouldBeADomainException() {
            assertThat(new NotFoundException("portfolio", 1)).isInstanceOf(DomainException.class);
            assertThat(new ValidationException("bad input")).isInstanceOf(DomainException.class);
            assertThat(new InsufficientQuantityException("AAPL", BigDecimal.ONE, BigDecimal.ZERO))
                    .isInstanceOf(DomainException.class);
            assertThat(new CurrencyMismatchException(CurrencyCode.USD, CurrencyCode.EUR))
                    .isInstanceOf(DomainException.class);
            assertThat(new UpstreamException("YAHOO", "down")).isInstanceOf(DomainException.class);
        }

        @Test
        void everySubclassShouldMapToADifferentDistinctSlugOrStatusPair() {
            // guards against a copy-paste that leaves two exceptions sharing a slug
            DomainException[] all = {
                    new NotFoundException("portfolio", 1),
                    new ValidationException("bad input"),
                    new InsufficientQuantityException("AAPL", BigDecimal.ONE, BigDecimal.ZERO),
                    new CurrencyMismatchException(CurrencyCode.USD, CurrencyCode.EUR),
                    new UpstreamException("YAHOO", "down"),
            };

            long distinctSlugs = java.util.Arrays.stream(all).map(DomainException::problemType).distinct().count();

            assertThat(distinctSlugs).isEqualTo(all.length);
        }
    }
}
