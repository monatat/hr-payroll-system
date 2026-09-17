package com.hrpayroll.backend.payroll;

/**
 * HTTP response DTO for {@code POST /api/payroll-runs/{id}/generate-pdfs} -- deliberately separate
 * from {@link PayrollPdfGenerationResult} (the internal service result) so the HTTP contract can
 * evolve independently. Exposes only ids/counts/status; never a filesystem path, employee
 * identity, salary/deduction value, or exception text.
 */
public record PayrollPdfGenerationResponse(
        Long payrollRunId, PayrollRunStatus status, int generatedCount, int failedCount, int skippedCount) {

    public static PayrollPdfGenerationResponse from(PayrollPdfGenerationResult result) {
        return new PayrollPdfGenerationResponse(
                result.payrollRunId(),
                result.finalStatus(),
                result.generatedCount(),
                result.failedCount(),
                result.skippedCount());
    }
}
