package com.hrpayroll.backend.payroll;

/**
 * Carries a payroll run's failure: the run id, a stable {@link PayrollFailureCode}, and a
 * sanitized business-level message safe to store as {@code payroll_runs.failure_reason} --
 * never a stack trace, raw SQL, a database exception message, a salary/deduction amount, or
 * a credential/token.
 */
public class PayrollRunFailedException extends RuntimeException {

    private final Long payrollRunId;
    private final PayrollFailureCode failureCode;
    private final String safeMessage;

    public PayrollRunFailedException(Long payrollRunId, PayrollFailureCode failureCode, String safeMessage) {
        this(payrollRunId, failureCode, safeMessage, null);
    }

    public PayrollRunFailedException(Long payrollRunId, PayrollFailureCode failureCode, String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        this.payrollRunId = payrollRunId;
        this.failureCode = failureCode;
        this.safeMessage = safeMessage;
    }

    public Long getPayrollRunId() {
        return payrollRunId;
    }

    public PayrollFailureCode getFailureCode() {
        return failureCode;
    }

    public String getSafeMessage() {
        return safeMessage;
    }
}
