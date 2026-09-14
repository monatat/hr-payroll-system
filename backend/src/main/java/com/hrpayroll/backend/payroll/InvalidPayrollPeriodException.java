package com.hrpayroll.backend.payroll;

/** Thrown when a requested payroll period's month/year fails validation, before any {@code payroll_runs} row is inserted. */
public class InvalidPayrollPeriodException extends RuntimeException {

    public InvalidPayrollPeriodException(String message) {
        super(message);
    }
}
