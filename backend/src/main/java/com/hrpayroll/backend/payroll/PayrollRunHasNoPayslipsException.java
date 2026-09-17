package com.hrpayroll.backend.payroll;

/**
 * Thrown when PDF generation is requested for a CALCULATED payroll run that has zero payslips --
 * a state that should never normally exist (a CALCULATED run always has at least one payslip,
 * since calculation fails all-or-nothing with zero candidates), but generation must never
 * vacuously "complete" such a run.
 */
public class PayrollRunHasNoPayslipsException extends RuntimeException {

    private final Long payrollRunId;

    public PayrollRunHasNoPayslipsException(Long payrollRunId) {
        super("Payroll run " + payrollRunId + " has no payslips to generate PDFs for.");
        this.payrollRunId = payrollRunId;
    }

    public Long getPayrollRunId() {
        return payrollRunId;
    }
}
