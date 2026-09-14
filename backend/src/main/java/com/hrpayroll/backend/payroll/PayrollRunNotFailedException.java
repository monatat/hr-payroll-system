package com.hrpayroll.backend.payroll;

/** Thrown when retry is attempted on a payroll run that is not currently FAILED. */
public class PayrollRunNotFailedException extends RuntimeException {

    private final Long payrollRunId;

    public PayrollRunNotFailedException(Long payrollRunId) {
        super("Payroll run " + payrollRunId + " is not FAILED and cannot be retried.");
        this.payrollRunId = payrollRunId;
    }

    public Long getPayrollRunId() {
        return payrollRunId;
    }
}
