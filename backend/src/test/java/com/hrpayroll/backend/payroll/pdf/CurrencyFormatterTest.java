package com.hrpayroll.backend.payroll.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CurrencyFormatter} -- a pure, Spring-free BigDecimal-to-String formatter.
 * Never converts through double/float anywhere in its path.
 */
class CurrencyFormatterTest {

    @Test
    void formatsZero() {
        assertThat(CurrencyFormatter.format(BigDecimal.ZERO)).isEqualTo("RM 0.00");
    }

    @Test
    void formatsAWholeValue() {
        assertThat(CurrencyFormatter.format(new BigDecimal("12"))).isEqualTo("RM 12.00");
    }

    @Test
    void formatsAOneDecimalValue() {
        assertThat(CurrencyFormatter.format(new BigDecimal("12.5"))).isEqualTo("RM 12.50");
    }

    @Test
    void formatsThousandsWithASeparator() {
        assertThat(CurrencyFormatter.format(new BigDecimal("1234.56"))).isEqualTo("RM 1,234.56");
    }

    @Test
    void formatsMillionsWithMultipleSeparators() {
        assertThat(CurrencyFormatter.format(new BigDecimal("1234567.8"))).isEqualTo("RM 1,234,567.80");
    }

    @Test
    void roundsHalfUpAtTheThirdDecimal() {
        // 12.505 is exactly halfway -- HALF_UP rounds away from zero to 12.51 (HALF_EVEN would give 12.50).
        assertThat(CurrencyFormatter.format(new BigDecimal("12.505"))).isEqualTo("RM 12.51");
    }

    @Test
    void formatsANegativeValue() {
        assertThat(CurrencyFormatter.format(new BigDecimal("-1234.56"))).isEqualTo("-RM 1,234.56");
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> CurrencyFormatter.format(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("value");
    }

    @Test
    void formatsVeryLargeValuesWithoutScientificNotation() {
        assertThat(CurrencyFormatter.format(new BigDecimal("123456789012345.67")))
                .isEqualTo("RM 123,456,789,012,345.67");
    }

    @Test
    void doesNotMutateTheInputBigDecimal() {
        BigDecimal input = new BigDecimal("1234.5");

        CurrencyFormatter.format(input);

        assertThat(input).isEqualByComparingTo("1234.5");
        assertThat(input.scale()).isEqualTo(1);
        assertThat(input.toPlainString()).isEqualTo("1234.5");
    }
}
