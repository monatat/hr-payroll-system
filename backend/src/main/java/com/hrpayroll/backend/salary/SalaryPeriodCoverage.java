package com.hrpayroll.backend.salary;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Pure, Spring/JPA/database-free coverage rule shared by
 * {@link SalaryStructureService} and the payroll package: does a salary
 * structure's effective range cover a period in full? A {@code null}
 * {@code effectiveTo} means open-ended (covers to +infinity). No
 * combination/proration across multiple structures is implemented here or
 * anywhere else -- a single structure must individually satisfy this rule.
 */
public final class SalaryPeriodCoverage {

    private SalaryPeriodCoverage() {
    }

    public static boolean covers(LocalDate effectiveFrom, LocalDate effectiveTo, LocalDate periodStart, LocalDate periodEnd) {
        Objects.requireNonNull(effectiveFrom, "effectiveFrom must not be null");
        Objects.requireNonNull(periodStart, "periodStart must not be null");
        Objects.requireNonNull(periodEnd, "periodEnd must not be null");

        boolean startsOnOrBeforePeriodStart = !effectiveFrom.isAfter(periodStart);
        boolean endsOnOrAfterPeriodEnd = effectiveTo == null || !effectiveTo.isBefore(periodEnd);
        return startsOnOrBeforePeriodStart && endsOnOrAfterPeriodEnd;
    }
}
