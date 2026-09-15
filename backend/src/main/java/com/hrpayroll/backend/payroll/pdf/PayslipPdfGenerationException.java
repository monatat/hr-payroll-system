package com.hrpayroll.backend.payroll.pdf;

/**
 * Thrown when a payslip PDF cannot be rendered. The message is always a safe, generic,
 * business-level sentence -- never a PDFBox class name, an {@code IOException} message, a stack
 * trace, a salary value, employee detail, or document content. The underlying cause is preserved
 * for server-side diagnostics only.
 */
public class PayslipPdfGenerationException extends RuntimeException {

    public PayslipPdfGenerationException(String safeMessage, Throwable cause) {
        super(safeMessage, cause);
    }
}
