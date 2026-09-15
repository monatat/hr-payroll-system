package com.hrpayroll.backend.payroll.pdf;

import java.math.BigDecimal;

/**
 * Immutable snapshot of exactly the fields a payslip PDF may render, deliberately separate from
 * the {@code Payslip}/{@code PayrollRun} JPA entities so {@link PayslipPdfGenerator} stays
 * Spring/JPA-free and never risks touching a lazy association. Every field here must already come
 * from a payslip's own stored snapshot columns (or its run's period) -- never re-read from current
 * Employee/Department/SalaryStructure state. Deliberately excludes IC number, bank details, phone
 * number, login email, database ids, rate percentages, audit data, and the raw {@code pdfPath}.
 */
public record PayslipPdfData(
        int periodMonth,
        int periodYear,
        String employeeCode,
        String employeeName,
        String departmentName,
        String jobTitle,
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
