package com.hrpayroll.backend.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PayrollPeriod} -- pure period derivation/validation,
 * no Spring/JPA/database dependency. Validation happens here, before any
 * {@code payroll_runs} row is ever inserted.
 */
class PayrollPeriodTest {

    @Test
    void derivesFirstAndLastCalendarDateOfAnOrdinaryMonth() {
        PayrollPeriod period = PayrollPeriod.of(9, 2026);

        assertThat(period.periodStart()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(period.periodEnd()).isEqualTo(LocalDate.of(2026, 9, 30));
    }

    @Test
    void februaryInALeapYearHas29Days() {
        // 2000 is a leap year (divisible by 400).
        PayrollPeriod period = PayrollPeriod.of(2, 2000);

        assertThat(period.periodStart()).isEqualTo(LocalDate.of(2000, 2, 1));
        assertThat(period.periodEnd()).isEqualTo(LocalDate.of(2000, 2, 29));
    }

    @Test
    void februaryInACenturyNonLeapYearHas28Days() {
        // 2100 is divisible by 100 but not by 400 -- not a leap year, despite being at our upper year bound.
        PayrollPeriod period = PayrollPeriod.of(2, 2100);

        assertThat(period.periodStart()).isEqualTo(LocalDate.of(2100, 2, 1));
        assertThat(period.periodEnd()).isEqualTo(LocalDate.of(2100, 2, 28));
    }

    @Test
    void monthZeroIsRejected() {
        assertThatThrownBy(() -> PayrollPeriod.of(0, 2026))
                .isInstanceOf(InvalidPayrollPeriodException.class)
                .hasMessageContaining("month");
    }

    @Test
    void monthThirteenIsRejected() {
        assertThatThrownBy(() -> PayrollPeriod.of(13, 2026))
                .isInstanceOf(InvalidPayrollPeriodException.class)
                .hasMessageContaining("month");
    }

    @Test
    void yearBelow2000IsRejected() {
        assertThatThrownBy(() -> PayrollPeriod.of(1, 1999))
                .isInstanceOf(InvalidPayrollPeriodException.class)
                .hasMessageContaining("year");
    }

    @Test
    void yearAbove2100IsRejected() {
        assertThatThrownBy(() -> PayrollPeriod.of(1, 2101))
                .isInstanceOf(InvalidPayrollPeriodException.class)
                .hasMessageContaining("year");
    }

    @Test
    void nullMonthIsRejected() {
        assertThatThrownBy(() -> PayrollPeriod.of(null, 2026))
                .isInstanceOf(InvalidPayrollPeriodException.class)
                .hasMessageContaining("month");
    }

    @Test
    void nullYearIsRejected() {
        assertThatThrownBy(() -> PayrollPeriod.of(1, null))
                .isInstanceOf(InvalidPayrollPeriodException.class)
                .hasMessageContaining("year");
    }
}
