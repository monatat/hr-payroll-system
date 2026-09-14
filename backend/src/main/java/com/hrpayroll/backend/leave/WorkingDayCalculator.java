package com.hrpayroll.backend.leave;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * Stateless working-day counter shared by leave-request creation (Phase 7B)
 * and its tests. Dates are inclusive; Saturday/Sunday are excluded; public
 * holidays are out of v1 scope. Callers are responsible for validating
 * {@code startDate <= endDate} before calling this -- it assumes a valid
 * (possibly zero-length) range.
 */
public final class WorkingDayCalculator {

    private WorkingDayCalculator() {
    }

    /**
     * Counts Monday-Friday dates in the inclusive range
     * [startDate, endDate]. Returns a BigDecimal built directly from the
     * integral count (via {@link BigDecimal#valueOf(long)}) rather than
     * through a double, so no floating-point conversion is ever involved.
     */
    public static BigDecimal countInclusive(LocalDate startDate, LocalDate endDate) {
        long workingDays = startDate.datesUntil(endDate.plusDays(1))
                .filter(WorkingDayCalculator::isWeekday)
                .count();
        return BigDecimal.valueOf(workingDays);
    }

    private static boolean isWeekday(LocalDate date) {
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        return dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY;
    }
}
