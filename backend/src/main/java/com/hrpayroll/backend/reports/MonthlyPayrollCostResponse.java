package com.hrpayroll.backend.reports;

import java.math.BigDecimal;

/**
 * HR_ADMIN-facing payroll-cost DTO for one month, mapped directly from the Phase 10A repository
 * projection {@link MonthlyPayrollCostSummary} -- kept as a separate type so the HTTP response
 * contract never depends on (or leaks) the persistence-layer projection shape directly.
 */
public record MonthlyPayrollCostResponse(int periodMonth, BigDecimal totalGrossPay) {

    public static MonthlyPayrollCostResponse from(MonthlyPayrollCostSummary summary) {
        return new MonthlyPayrollCostResponse(summary.periodMonth(), summary.totalGrossPay());
    }
}
