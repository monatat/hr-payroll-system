package com.hrpayroll.backend.payroll.pdf;

/**
 * Thrown when a stored payslip PDF path resolves safely under the configured root but no regular
 * file exists there (missing, deleted externally, or a directory in its place). Distinct from
 * {@link PayrollPdfStorageException}, which covers a malformed/traversal path or a write failure --
 * this one specifically means "the path is fine, the file is not there."
 */
public class PayslipPdfFileMissingException extends RuntimeException {

    public PayslipPdfFileMissingException() {
        super("The requested payslip PDF file is missing.");
    }
}
