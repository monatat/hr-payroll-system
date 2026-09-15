package com.hrpayroll.backend.payroll.pdf;

/**
 * Thrown for any payslip PDF storage-layer failure: the configured root cannot be initialized, a
 * write fails, or a resolved/stored path is invalid or falls outside the configured root. The
 * message is always a safe, generic, business-level sentence -- never an absolute path, an
 * {@code IOException} message, a username, or a machine path. The underlying cause is preserved
 * for server-side diagnostics only.
 */
public class PayrollPdfStorageException extends RuntimeException {

    public PayrollPdfStorageException(String safeMessage) {
        super(safeMessage);
    }

    public PayrollPdfStorageException(String safeMessage, Throwable cause) {
        super(safeMessage, cause);
    }
}
