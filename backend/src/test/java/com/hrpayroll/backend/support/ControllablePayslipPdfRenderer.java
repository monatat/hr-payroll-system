package com.hrpayroll.backend.support;

import com.hrpayroll.backend.payroll.pdf.PayslipPdfData;
import com.hrpayroll.backend.payroll.pdf.PayslipPdfGenerationException;
import com.hrpayroll.backend.payroll.pdf.PayslipPdfRenderer;

/**
 * Test-only {@link PayslipPdfRenderer} that delegates to a real renderer but can be armed to throw
 * {@link PayslipPdfGenerationException} exactly once for a chosen employee code -- the deterministic
 * one-time-failure seam Phase 8C-2B1's partial-failure/retry tests need, without relying on OS
 * permissions or mutating any immutable production snapshot. The armed failure clears itself after
 * firing, so calling generation again for the same employee succeeds normally, modeling a genuine
 * retry.
 */
public final class ControllablePayslipPdfRenderer implements PayslipPdfRenderer {

    private final PayslipPdfRenderer delegate;
    private volatile String employeeCodeToFailOnce;

    public ControllablePayslipPdfRenderer(PayslipPdfRenderer delegate) {
        this.delegate = delegate;
    }

    public void failOnceFor(String employeeCode) {
        this.employeeCodeToFailOnce = employeeCode;
    }

    /** Clears any armed one-time failure -- call between tests sharing the same Spring context/bean. */
    public void reset() {
        this.employeeCodeToFailOnce = null;
    }

    @Override
    public byte[] generate(PayslipPdfData data) {
        if (data.employeeCode().equals(employeeCodeToFailOnce)) {
            employeeCodeToFailOnce = null;
            throw new PayslipPdfGenerationException("Simulated deterministic rendering failure for a test.", null);
        }
        return delegate.generate(data);
    }
}
