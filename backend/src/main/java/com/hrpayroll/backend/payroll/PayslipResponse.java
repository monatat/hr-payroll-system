package com.hrpayroll.backend.payroll;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Payslip DTO (HR_ADMIN and EMPLOYEE-owner facing), built entirely from the entity's own stored
 * snapshot/monetary fields -- never by re-reading current Employee/Department/SalaryStructure
 * state. Deliberately excludes {@code pdfPath}: no raw filesystem path is ever serialized.
 * {@code periodMonth}/{@code periodYear} come from the associated {@link PayrollRun}, not
 * {@code createdAt} -- a run can be created well after (or before) the period it covers.
 */
public record PayslipResponse(
        Long id,
        Long payrollRunId,
        Long employeeId,
        String employeeCodeSnapshot,
        String employeeNameSnapshot,
        String departmentNameSnapshot,
        String jobTitleSnapshot,
        BigDecimal basePay,
        BigDecimal housingAllowance,
        BigDecimal transportAllowance,
        BigDecimal grossPay,
        BigDecimal epfDeduction,
        BigDecimal socsoDeduction,
        BigDecimal eisDeduction,
        BigDecimal incomeTax,
        BigDecimal totalDeductions,
        BigDecimal netPay,
        PdfStatus pdfStatus,
        LocalDateTime createdAt,
        Integer periodMonth,
        Integer periodYear) {

    public static PayslipResponse from(Payslip payslip) {
        return new PayslipResponse(
                payslip.getId(),
                payslip.getPayrollRun().getId(),
                payslip.getEmployee().getId(),
                payslip.getEmployeeCodeSnapshot(),
                payslip.getEmployeeNameSnapshot(),
                payslip.getDepartmentNameSnapshot(),
                payslip.getJobTitleSnapshot(),
                payslip.getBasePay(),
                payslip.getHousingAllowance(),
                payslip.getTransportAllowance(),
                payslip.getGrossPay(),
                payslip.getEpfDeduction(),
                payslip.getSocsoDeduction(),
                payslip.getEisDeduction(),
                payslip.getIncomeTax(),
                payslip.getTotalDeductions(),
                payslip.getNetPay(),
                payslip.getPdfStatus(),
                payslip.getCreatedAt(),
                payslip.getPayrollRun().getPeriodMonth(),
                payslip.getPayrollRun().getPeriodYear());
    }
}
