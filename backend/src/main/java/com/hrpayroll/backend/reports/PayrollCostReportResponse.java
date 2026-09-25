package com.hrpayroll.backend.reports;

import java.math.BigDecimal;
import java.util.List;

/**
 * HR_ADMIN-facing payroll-cost report for one year: an authoritative annual total (from
 * {@code PayslipRepository.findAnnualGrossPayTotal} -- never summed here from monthlyTotals) plus
 * the authoritative monthly breakdown (from {@code findMonthlyGrossPayTotals}), already ordered by
 * month ascending. No department breakdown and no employee-level data, per the approved Phase 10
 * report contract. {@code monthlyTotals} is defensively copied into an immutable snapshot so a
 * caller can never mutate a cached response.
 */
public record PayrollCostReportResponse(int year, BigDecimal annualTotalGrossPay, List<MonthlyPayrollCostResponse> monthlyTotals) {

    public PayrollCostReportResponse {
        monthlyTotals = List.copyOf(monthlyTotals);
    }
}
