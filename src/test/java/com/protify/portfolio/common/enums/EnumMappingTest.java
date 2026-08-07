package com.protify.portfolio.common.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class EnumMappingTest {

    @Nested
    class AssetTypeMapping {

        @Test
        void shouldRoundTripEveryConstantThroughName() {
            for (AssetType type : AssetType.values()) {
                assertThat(AssetType.fromDbValue(type.name())).contains(type);
            }
        }

        @Test
        void shouldReturnEmptyForUnrecognisedValue() {
            assertThat(AssetType.fromDbValue("NOT_A_VALUE")).isEmpty();
        }

        @Test
        void shouldReturnEmptyForNull() {
            assertThat(AssetType.fromDbValue(null)).isEmpty();
        }

        @Test
        void shouldReturnEmptyForBlank() {
            assertThat(AssetType.fromDbValue("")).isEmpty();
        }
    }

    @Nested
    class TransactionTypeMapping {

        @Test
        void shouldRoundTripEveryConstantThroughName() {
            for (TransactionType type : TransactionType.values()) {
                assertThat(TransactionType.fromDbValue(type.name())).contains(type);
            }
        }

        @Test
        void shouldReturnEmptyForUnrecognisedValue() {
            assertThat(TransactionType.fromDbValue("NOT_A_VALUE")).isEmpty();
        }

        @Test
        void shouldReturnEmptyForNull() {
            assertThat(TransactionType.fromDbValue(null)).isEmpty();
        }

        @Test
        void shouldReturnEmptyForBlank() {
            assertThat(TransactionType.fromDbValue("")).isEmpty();
        }
    }

    @Nested
    class CurrencyCodeMapping {

        @Test
        void shouldRoundTripEveryConstantThroughName() {
            for (CurrencyCode code : CurrencyCode.values()) {
                assertThat(CurrencyCode.fromDbValue(code.name())).contains(code);
            }
        }

        @Test
        void shouldReturnEmptyForUnrecognisedValue() {
            assertThat(CurrencyCode.fromDbValue("NOT_A_VALUE")).isEmpty();
        }

        @Test
        void shouldReturnEmptyForNull() {
            assertThat(CurrencyCode.fromDbValue(null)).isEmpty();
        }

        @Test
        void shouldReturnEmptyForBlank() {
            assertThat(CurrencyCode.fromDbValue("")).isEmpty();
        }
    }

    @Nested
    class PriceSourceMapping {

        @Test
        void shouldRoundTripEveryConstantThroughName() {
            for (PriceSource source : PriceSource.values()) {
                assertThat(PriceSource.fromDbValue(source.name())).contains(source);
            }
        }

        @Test
        void shouldReturnEmptyForUnrecognisedValue() {
            assertThat(PriceSource.fromDbValue("NOT_A_VALUE")).isEmpty();
        }

        @Test
        void shouldReturnEmptyForNull() {
            assertThat(PriceSource.fromDbValue(null)).isEmpty();
        }

        @Test
        void shouldReturnEmptyForBlank() {
            assertThat(PriceSource.fromDbValue("")).isEmpty();
        }
    }

    @Nested
    class FxSourceMapping {

        @Test
        void shouldRoundTripEveryConstantThroughName() {
            for (FxSource source : FxSource.values()) {
                assertThat(FxSource.fromDbValue(source.name())).contains(source);
            }
        }

        @Test
        void shouldReturnEmptyForUnrecognisedValue() {
            assertThat(FxSource.fromDbValue("NOT_A_VALUE")).isEmpty();
        }

        @Test
        void shouldReturnEmptyForNull() {
            assertThat(FxSource.fromDbValue(null)).isEmpty();
        }

        @Test
        void shouldReturnEmptyForBlank() {
            assertThat(FxSource.fromDbValue("")).isEmpty();
        }
    }
}
