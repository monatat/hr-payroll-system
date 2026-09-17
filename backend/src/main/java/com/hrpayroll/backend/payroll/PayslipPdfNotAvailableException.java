package com.hrpayroll.backend.payroll;

/** Thrown when a payslip PDF is requested while its {@code pdfStatus} is PENDING or FAILED. */
public class PayslipPdfNotAvailableException extends RuntimeException {

    public PayslipPdfNotAvailableException() {
        super("The payslip PDF is not yet available.");
    }
}
