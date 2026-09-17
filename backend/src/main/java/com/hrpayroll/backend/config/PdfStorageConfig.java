package com.hrpayroll.backend.config;

import com.hrpayroll.backend.payroll.pdf.PayslipPdfGenerator;
import com.hrpayroll.backend.payroll.pdf.PayslipPdfRenderer;
import com.hrpayroll.backend.payroll.pdf.PdfStorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Wires the Phase 8C-2A PDF foundation into Spring with the smallest reasonable configuration.
 * {@link PdfStorageService}'s constructor eagerly creates the configured storage directory, so it
 * is registered as an ordinary bean here but only ever obtained by {@code PayrollPdfService}
 * (Phase 8C-2B1) through an {@code ObjectProvider}, resolved lazily inside its transactional
 * generation method -- never injected as a direct, eagerly-resolved constructor dependency. This
 * means an unrelated {@code @SpringBootTest} that boots the full application context but never
 * calls PDF generation never triggers this bean's construction, so it never creates the
 * development default directory ({@code ./data/payroll-pdfs}) merely by starting up.
 *
 * <p>{@code @Lazy} on the bean method is required in addition to the {@code ObjectProvider}-based
 * consumption: Spring eagerly pre-instantiates every non-lazy singleton bean definition during
 * context refresh regardless of whether any consumer actually asks for it yet, so without
 * {@code @Lazy} here the directory would still be created at startup even though nothing directly
 * injects a concrete {@code PdfStorageService} reference. Because the only consumer resolves this
 * bean through {@code ObjectProvider.getObject()} rather than a plain injected field/parameter, no
 * CGLIB proxy of {@code PdfStorageService} (a {@code final} class) is ever required.
 */
@Configuration
public class PdfStorageConfig {

    @Bean
    @Lazy
    public PdfStorageService pdfStorageService(@Value("${app.payroll.pdf-storage-dir}") String pdfStorageDir) {
        return new PdfStorageService(pdfStorageDir);
    }

    @Bean
    public PayslipPdfRenderer payslipPdfRenderer() {
        return new PayslipPdfGenerator();
    }
}
