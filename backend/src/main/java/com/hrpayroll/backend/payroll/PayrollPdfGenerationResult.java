package com.hrpayroll.backend.payroll;

/**
 * Immutable outcome of one {@code PayrollPdfService.generatePdfs} invocation -- a pure service
 * result, never an HTTP response and never a JPA entity. Never exposes a filesystem path,
 * compensation value, employee detail, or exception text.
 *
 * <p>{@code generatedCount}/{@code failedCount} count only PENDING/FAILED payslips actually
 * processed during *this* invocation; {@code skippedCount} counts payslips that were already
 * GENERATED before this invocation started. A COMPLETED idempotent no-op returns
 * {@code generatedCount = 0}, {@code failedCount = 0}, and {@code skippedCount} equal to the run's
 * total payslip count.
 */
public record PayrollPdfGenerationResult(
        Long payrollRunId, PayrollRunStatus finalStatus, int generatedCount, int failedCount, int skippedCount) {
}
