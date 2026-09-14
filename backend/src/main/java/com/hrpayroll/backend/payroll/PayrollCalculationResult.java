package com.hrpayroll.backend.payroll;

import java.math.BigDecimal;

/**
 * Immutable result of one employee's payroll calculation for a period.
 * Every amount is normalized to scale 2. Pure data -- no JPA, no behavior
 * beyond the accessors.
 */
public record PayrollCalculationResult(
        BigDecimal basePay,
        BigDecimal housingAllowance,
        BigDecimal transportAllowance,
        BigDecimal grossPay,
        BigDecimal epfDeduction,
        BigDecimal socsoDeduction,
        BigDecimal eisDeduction,
        BigDecimal incomeTax,
        BigDecimal totalDeductions,
        BigDecimal netPay) {
}
