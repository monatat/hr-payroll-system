package com.hrpayroll.backend.payroll;

/** Thrown when a requested payslip id does not exist. */
public class PayslipNotFoundException extends RuntimeException {

    public PayslipNotFoundException() {
        super("Payslip not found.");
    }
}
