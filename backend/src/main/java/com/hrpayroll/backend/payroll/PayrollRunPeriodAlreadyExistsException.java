package com.hrpayroll.backend.payroll;

/** Thrown when a payroll run for the requested (month, year) already exists -- mapped from the {@code uq_payroll_runs_period} constraint. */
public class PayrollRunPeriodAlreadyExistsException extends RuntimeException {

    private final int periodMonth;
    private final int periodYear;

    public PayrollRunPeriodAlreadyExistsException(int periodMonth, int periodYear) {
        super("A payroll run for " + periodMonth + "/" + periodYear + " already exists.");
        this.periodMonth = periodMonth;
        this.periodYear = periodYear;
    }

    public int getPeriodMonth() {
        return periodMonth;
    }

    public int getPeriodYear() {
        return periodYear;
    }
}
