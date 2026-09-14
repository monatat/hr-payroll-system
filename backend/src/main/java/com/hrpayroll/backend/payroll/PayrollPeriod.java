package com.hrpayroll.backend.payroll;

import java.time.LocalDate;

/**
 * Pure period derivation/validation, no Spring/JPA/database dependency.
 * Validated and derived before any {@code payroll_runs} row is ever
 * inserted -- an invalid period never reaches persistence.
 */
public record PayrollPeriod(int month, int year, LocalDate periodStart, LocalDate periodEnd) {

    public static PayrollPeriod of(Integer month, Integer year) {
        if (month == null || month < 1 || month > 12) {
            throw new InvalidPayrollPeriodException("month must be between 1 and 12: was " + month);
        }
        if (year == null || year < 2000 || year > 2100) {
            throw new InvalidPayrollPeriodException("year must be between 2000 and 2100: was " + year);
        }
        LocalDate periodStart = LocalDate.of(year, month, 1);
        LocalDate periodEnd = periodStart.withDayOfMonth(periodStart.lengthOfMonth());
        return new PayrollPeriod(month, year, periodStart, periodEnd);
    }
}
