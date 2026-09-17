package com.hrpayroll.backend.payroll.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Proves that merely booting the Spring context -- using the real, unoverridden
 * {@code app.payroll.pdf-storage-dir} default -- never creates the development PDF storage
 * directory. {@link PdfStorageService}'s constructor eagerly calls {@code Files.createDirectories},
 * so this only holds if the bean is genuinely never instantiated by context startup (see
 * {@code PdfStorageConfig}'s {@code @Lazy} bean definition plus {@code ObjectProvider}-based
 * consumption in the future orchestration service).
 */
@SpringBootTest
class PayrollPdfWiringTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void bootingTheContextNeverCreatesTheDevelopmentPdfStorageDirectory() {
        assertThat(applicationContext).isNotNull();

        assertThat(Files.exists(Path.of("./data/payroll-pdfs"))).isFalse();
        assertThat(Files.exists(Path.of("data/payroll-pdfs"))).isFalse();
    }
}
