package com.hrpayroll.backend.payroll;

/** Thrown when a requested payroll run id does not exist. */
public class PayrollRunNotFoundException extends RuntimeException {

    public PayrollRunNotFoundException() {
        super("Payroll run not found.");
    }
}
