package com.hrpayroll.backend.leave;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link WorkingDayCalculator} -- no Spring context, no
 * database. Dates are inclusive; Saturday/Sunday are excluded; public
 * holidays are out of v1 scope (see Phase 7 approved rules).
 */
class WorkingDayCalculatorTest {

    @Test
    void mondayThroughFridayReturnsFive() {
        // 2026-09-07 is a Monday, 2026-09-11 is the following Friday.
        LocalDate monday = LocalDate.of(2026, 9, 7);
        LocalDate friday = LocalDate.of(2026, 9, 11);

        assertThat(WorkingDayCalculator.countInclusive(monday, friday)).isEqualByComparingTo(BigDecimal.valueOf(5));
    }

    @Test
    void singleWeekdayReturnsOne() {
        LocalDate wednesday = LocalDate.of(2026, 9, 9);

        assertThat(WorkingDayCalculator.countInclusive(wednesday, wednesday)).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void weekendDaysInsideALongerRangeAreExcluded() {
        // 2026-09-07 (Mon) .. 2026-09-14 (Mon): 8 calendar days, 2 of them
        // (Sat 09-12, Sun 09-13) are weekend -- 6 working days remain.
        LocalDate start = LocalDate.of(2026, 9, 7);
        LocalDate end = LocalDate.of(2026, 9, 14);

        assertThat(WorkingDayCalculator.countInclusive(start, end)).isEqualByComparingTo(BigDecimal.valueOf(6));
    }

    @Test
    void saturdayThroughSundayReturnsZero() {
        LocalDate saturday = LocalDate.of(2026, 9, 12);
        LocalDate sunday = LocalDate.of(2026, 9, 13);

        assertThat(WorkingDayCalculator.countInclusive(saturday, sunday)).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
