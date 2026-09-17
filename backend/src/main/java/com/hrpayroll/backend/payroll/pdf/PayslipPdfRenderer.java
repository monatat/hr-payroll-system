package com.hrpayroll.backend.payroll.pdf;

/**
 * Minimal rendering seam so {@code PayrollPdfService} (Phase 8C-2B1) depends on an abstraction
 * rather than the concrete {@link PayslipPdfGenerator} -- needed only so a test can substitute a
 * controllable renderer to deterministically force a one-time per-document failure. Not a general
 * plugin/job framework: exactly one method, one production implementation.
 */
public interface PayslipPdfRenderer {

    byte[] generate(PayslipPdfData data);
}
