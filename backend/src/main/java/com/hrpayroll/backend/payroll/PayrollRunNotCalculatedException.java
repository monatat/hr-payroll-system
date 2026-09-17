package com.hrpayroll.backend.payroll;

/** Thrown when PDF generation is requested for a payroll run that is not currently CALCULATED. */
public class PayrollRunNotCalculatedException extends RuntimeException {

    private final Long payrollRunId;

    public PayrollRunNotCalculatedException(Long payrollRunId) {
        super("Payroll run " + payrollRunId + " is not CALCULATED; PDF generation is not available.");
        this.payrollRunId = payrollRunId;
    }

    public Long getPayrollRunId() {
        return payrollRunId;
    }
}
