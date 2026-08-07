package com.protify.portfolio.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.protify.portfolio.common.enums.CurrencyCode;
import java.math.BigDecimal;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MoneyUtilsTest {

    @Nested
    class Rounding {

        @Test
        void shouldRoundMoneyToFourDecimalPlaces() {
            BigDecimal result = MoneyUtils.money(new BigDecimal("100.123456"));

            assertThat(result.scale()).isEqualTo(4);
            assertThat(result).isEqualByComparingTo("100.1235");
        }

        @Test
        void shouldRoundQuantityToSixDecimalPlaces() {
            BigDecimal result = MoneyUtils.quantity(new BigDecimal("10.123456789"));

            assertThat(result.scale()).isEqualTo(6);
            assertThat(result).isEqualByComparingTo("10.123457");
        }

        @Test
        void shouldRoundFxRateToEightDecimalPlaces() {
            BigDecimal result = MoneyUtils.fxRate(new BigDecimal("87.123456789123"));

            assertThat(result.scale()).isEqualTo(8);
            assertThat(result).isEqualByComparingTo("87.12345679");
        }

        @Test
        void shouldRoundHalfUpAtExactlyPointFiveForMoneyScale() {
            assertThat(MoneyUtils.money(new BigDecimal("1.00005"))).isEqualByComparingTo("1.0001");
        }

        @Test
        void shouldRoundHalfUpAwayFromZeroAtExactlyPointFiveForNegativeMoney() {
            assertThat(MoneyUtils.money(new BigDecimal("-1.00005"))).isEqualByComparingTo("-1.0001");
        }

        @Test
        void shouldRoundHalfUpAtExactlyPointFiveForQuantityScale() {
            assertThat(MoneyUtils.quantity(new BigDecimal("1.1234565"))).isEqualByComparingTo("1.123457");
        }

        @Test
        void shouldRoundHalfUpAtExactlyPointFiveForFxScale() {
            assertThat(MoneyUtils.fxRate(new BigDecimal("1.123456785"))).isEqualByComparingTo("1.12345679");
        }
    }

    @Nested
    class ZeroAndSign {

        @Test
        void shouldTreatDifferentlyScaledZeroAsZero() {
            assertThat(MoneyUtils.isZero(new BigDecimal("0.0000"))).isTrue();
            assertThat(MoneyUtils.isZero(BigDecimal.ZERO)).isTrue();
        }

        @Test
        void shouldNotTreatNonZeroAsZero() {
            assertThat(MoneyUtils.isZero(new BigDecimal("0.0001"))).isFalse();
        }

        @Test
        void shouldTreatNegativeValueAsNegative() {
            assertThat(MoneyUtils.isNegative(new BigDecimal("-0.0001"))).isTrue();
        }

        @Test
        void shouldNotTreatZeroOrPositiveAsNegative() {
            assertThat(MoneyUtils.isNegative(BigDecimal.ZERO)).isFalse();
            assertThat(MoneyUtils.isNegative(new BigDecimal("0.0001"))).isFalse();
        }
    }

    @Nested
    class SafeDivide {

        @Test
        void shouldReturnNullWhenDivisorIsZero() {
            assertThat(MoneyUtils.safeDivide(new BigDecimal("100"), BigDecimal.ZERO, 4)).isNull();
        }

        @Test
        void shouldReturnNullWhenDivisorIsDifferentlyScaledZero() {
            assertThat(MoneyUtils.safeDivide(new BigDecimal("100"), new BigDecimal("0.0000"), 4)).isNull();
        }

        @Test
        void shouldReturnQuotientAtRequestedScaleWhenDivisorIsNonZero() {
            BigDecimal result = MoneyUtils.safeDivide(new BigDecimal("10"), new BigDecimal("4"), 4);

            assertThat(result).isEqualByComparingTo("2.5000");
            assertThat(result.scale()).isEqualTo(4);
        }
    }

    @Nested
    class PctChange {

        @Test
        void shouldReturnNullPercentWhenBaseIsZero() {
            assertThat(MoneyUtils.pctChange(BigDecimal.ZERO, new BigDecimal("500"))).isNull();
        }

        @Test
        void shouldReturnNullWhenBaseIsDifferentlyScaledZero() {
            assertThat(MoneyUtils.pctChange(new BigDecimal("0.0000"), new BigDecimal("500"))).isNull();
        }

        @Test
        void shouldReturnPositivePercentWhenValueIncreases() {
            assertThat(MoneyUtils.pctChange(new BigDecimal("100"), new BigDecimal("110"))).isEqualByComparingTo("10.0000");
        }

        @Test
        void shouldReturnNegativePercentWhenValueDecreases() {
            assertThat(MoneyUtils.pctChange(new BigDecimal("100"), new BigDecimal("90"))).isEqualByComparingTo("-10.0000");
        }

        @Test
        void shouldReturnZeroPercentWhenValueUnchanged() {
            assertThat(MoneyUtils.pctChange(new BigDecimal("100"), new BigDecimal("100"))).isEqualByComparingTo("0.0000");
        }
    }

    @Nested
    class MoneyEquality {

        @Test
        void shouldConsiderMoneyEqualAcrossDifferentInputScales() {
            Money a = new Money(new BigDecimal("100.00"), CurrencyCode.USD);
            Money b = new Money(new BigDecimal("100.0000"), CurrencyCode.USD);

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        void shouldConsiderMoneyNotEqualForDifferentCurrencies() {
            Money usd = new Money(new BigDecimal("100.00"), CurrencyCode.USD);
            Money eur = new Money(new BigDecimal("100.00"), CurrencyCode.EUR);

            assertThat(usd).isNotEqualTo(eur);
        }

        @Test
        void shouldConsiderMoneyNotEqualForDifferentAmounts() {
            Money a = new Money(new BigDecimal("100.00"), CurrencyCode.USD);
            Money b = new Money(new BigDecimal("100.01"), CurrencyCode.USD);

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        void shouldNormaliseAmountScaleOnConstruction() {
            Money money = new Money(new BigDecimal("100"), CurrencyCode.USD);

            assertThat(money.amount().scale()).isEqualTo(4);
            assertThat(money.amount()).isEqualByComparingTo("100.0000");
        }

        @Test
        void shouldRejectNullCurrency() {
            assertThatThrownBy(() -> new Money(new BigDecimal("100.00"), null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void shouldRejectNullAmount() {
            assertThatThrownBy(() -> new Money(null, CurrencyCode.USD))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
