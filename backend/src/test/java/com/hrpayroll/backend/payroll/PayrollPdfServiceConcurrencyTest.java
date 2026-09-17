package com.hrpayroll.backend.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.hrpayroll.backend.support.PayrollTestFixtures;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Proves PDF-generation concurrency safety against a REAL PostgreSQL instance, using the real
 * PayrollPdfService and the real transaction manager. No mocks, no H2, no Testcontainers, no
 * synchronized blocks, no sleeps. Deliberately NOT annotated @Transactional -- fixture rows are
 * inserted via plain (auto-committing) JdbcTemplate calls so worker threads' own connections see
 * them, matching PayrollRunConcurrencyTest's established style exactly.
 */
@SpringBootTest
class PayrollPdfServiceConcurrencyTest {

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void pdfStorageProperties(DynamicPropertyRegistry registry) {
        registry.add("app.payroll.pdf-storage-dir", () -> storageRoot.toString());
    }

    @Autowired
    private PayrollPdfService payrollPdfService;

    @Autowired
    private PayrollRunService payrollRunService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private long adminUserId;

    @BeforeEach
    void setUp() {
        PayrollTestFixtures.cleanup(jdbcTemplate);
        long departmentId = PayrollTestFixtures.installDepartment(jdbcTemplate);
        adminUserId = PayrollTestFixtures.installAdminUser(jdbcTemplate, passwordEncoder);
        long employeeId = PayrollTestFixtures.insertEmployee(
                jdbcTemplate, departmentId, "PDFRACE", "001314010001", LocalDate.of(1999, 1, 1), "ACTIVE");
        PayrollTestFixtures.insertSalaryStructure(
                jdbcTemplate, employeeId, new BigDecimal("2000.00"), BigDecimal.ZERO, BigDecimal.ZERO, LocalDate.of(1999, 1, 1), null);
    }

    @AfterEach
    void tearDown() {
        PayrollTestFixtures.cleanup(jdbcTemplate);
    }

    @Test
    void concurrentGenerationForTheSameCalculatedRunOnlyOneInvocationPerformsGeneration() throws Exception {
        int month = 1;
        int year = PayrollTestFixtures.PDF_TEST_PERIOD_YEAR_MAX; // own reserved period, distinct from PayrollPdfServiceTest
        PayrollRun run = payrollRunService.createAndCalculate(month, year, adminUserId);

        CountDownLatch bothReady = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<PayrollPdfGenerationResult> task = () -> {
                bothReady.countDown();
                go.await(10, TimeUnit.SECONDS);
                return payrollPdfService.generatePdfs(run.getId(), adminUserId);
            };
            Future<PayrollPdfGenerationResult> futureA = pool.submit(task);
            Future<PayrollPdfGenerationResult> futureB = pool.submit(task);

            assertThat(bothReady.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            PayrollPdfGenerationResult resultA = futureA.get(20, TimeUnit.SECONDS);
            PayrollPdfGenerationResult resultB = futureB.get(20, TimeUnit.SECONDS);

            List<PayrollPdfGenerationResult> results = List.of(resultA, resultB);
            long generatorCount = results.stream().filter(r -> r.generatedCount() > 0).count();
            long noOpCount = results.stream()
                    .filter(r -> r.generatedCount() == 0 && r.failedCount() == 0 && r.skippedCount() == 1)
                    .count();
            assertThat(generatorCount).isEqualTo(1);
            assertThat(noOpCount).isEqualTo(1);
            assertThat(results).allMatch(r -> r.finalStatus() == PayrollRunStatus.COMPLETED);
        } finally {
            pool.shutdownNow();
        }

        String status = jdbcTemplate.queryForObject("SELECT status FROM payroll_runs WHERE id = ?", String.class, run.getId());
        assertThat(status).isEqualTo("COMPLETED");

        List<String> pdfPaths = jdbcTemplate.queryForList(
                "SELECT pdf_path FROM payslips WHERE payroll_run_id = ?", String.class, run.getId());
        assertThat(pdfPaths).hasSize(1);
        Path file = storageRoot.resolve(pdfPaths.get(0));
        assertThat(Files.isRegularFile(file)).isTrue();
        byte[] bytes = Files.readAllBytes(file);
        assertThat(new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");

        List<String> pdfStatuses =
                jdbcTemplate.queryForList("SELECT pdf_status FROM payslips WHERE payroll_run_id = ?", String.class, run.getId());
        assertThat(pdfStatuses).containsExactly("GENERATED");

        Integer finishedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE entity_name = 'PAYROLL_RUN' AND entity_id = ? AND action = 'PAYROLL_RUN_PDF_GENERATION_FINISHED'",
                Integer.class,
                run.getId());
        Integer completedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE entity_name = 'PAYROLL_RUN' AND entity_id = ? AND action = 'PAYROLL_RUN_COMPLETED'",
                Integer.class,
                run.getId());
        assertThat(finishedCount).isEqualTo(1);
        assertThat(completedCount).isEqualTo(1);
    }
}
