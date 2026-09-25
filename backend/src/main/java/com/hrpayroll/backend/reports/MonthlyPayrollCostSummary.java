package com.hrpayroll.backend.reports;

import java.math.BigDecimal;

/**
 * One month's total payroll cost (defined as {@code SUM(Payslip.grossPay)}) for a selected year.
 * Only present for months with at least one qualifying payslip -- see
 * {@code PayslipRepository.findMonthlyGrossPayTotals}. Deliberately carries no employee identity,
 * department, or any other monetary field (never basePay/netPay) -- this is a projection, not an
 * entity, and is never persisted.
 */
public record MonthlyPayrollCostSummary(int periodMonth, BigDecimal totalGrossPay) {
}
