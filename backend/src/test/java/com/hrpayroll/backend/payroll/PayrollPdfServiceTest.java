package com.hrpayroll.backend.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrpayroll.backend.payroll.pdf.PayslipPdfGenerator;
import com.hrpayroll.backend.payroll.pdf.PayslipPdfRenderer;
import com.hrpayroll.backend.support.ControllablePayslipPdfRenderer;
import com.hrpayroll.backend.support.PayrollTestFixtures;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Real-PostgreSQL integration tests for {@link PayrollPdfService} (Phase 8C-2B1). Every PDF file
 * is written under a per-class {@code @TempDir}, overridden via {@code @DynamicPropertySource} --
 * never the real development {@code ./data/payroll-pdfs} directory. Uses its own reserved period
 * band ({@code PayrollTestFixtures.PDF_TEST_PERIOD_YEAR_MIN}), distinct from every earlier payroll
 * test class's reserved years.
 */
@SpringBootTest
class PayrollPdfServiceTest {

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void pdfStorageProperties(DynamicPropertyRegistry registry) {
        registry.add("app.payroll.pdf-storage-dir", () -> storageRoot.toString());
    }

    @TestConfiguration
    static class TestRendererConfig {
        @Bean
        @Primary
        PayslipPdfRenderer testPayslipPdfRenderer() {
            return new ControllablePayslipPdfRenderer(new PayslipPdfGenerator());
        }
    }

    @Autowired
    private PayrollPdfService payrollPdfService;

    @Autowired
    private PayrollRunService payrollRunService;

    @Autowired
    private PayrollRunRepository payrollRunRepository;

    @Autowired
    private PayslipRepository payslipRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private PayslipPdfRenderer payslipPdfRenderer;

    private static final int YEAR = PayrollTestFixtures.PDF_TEST_PERIOD_YEAR_MIN;
    private static final LocalDate BEFORE_PERIOD = LocalDate.of(1999, 1, 1);

    private long departmentId;
    private long adminUserId;
    private final List<Long> extraRunIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        extraRunIds.clear();
        PayrollTestFixtures.cleanup(jdbcTemplate);
        departmentId = PayrollTestFixtures.installDepartment(jdbcTemplate);
        adminUserId = PayrollTestFixtures.installAdminUser(jdbcTemplate, passwordEncoder);
        controllableRenderer().reset();
        payrollPdfService.setPreFlushHookForTesting(() -> {
        });
    }

    @AfterEach
    void tearDown() {
        payrollPdfService.setPreFlushHookForTesting(() -> {
        });
        controllableRenderer().reset();
        PayrollTestFixtures.cleanup(jdbcTemplate, extraRunIds);
    }

    private ControllablePayslipPdfRenderer controllableRenderer() {
        return (ControllablePayslipPdfRenderer) payslipPdfRenderer;
    }

    private long insertCandidate(String codeSuffix, String ic) {
        return PayrollTestFixtures.insertEmployee(jdbcTemplate, departmentId, codeSuffix, ic, BEFORE_PERIOD, "ACTIVE");
    }

    private void coverFullMonth(long employeeId, int month, BigDecimal basePay) {
        LocalDate periodStart = LocalDate.of(YEAR, month, 1);
        PayrollTestFixtures.insertSalaryStructure(
                jdbcTemplate, employeeId, basePay, BigDecimal.ZERO, BigDecimal.ZERO, periodStart, null);
    }

    private static String extractText(byte[] pdfBytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private Map<String, Object> detailsAsMap(Long runId, String action) throws IOException {
        String json = jdbcTemplate.queryForObject(
                "SELECT details::text FROM audit_logs WHERE entity_name = 'PAYROLL_RUN' AND entity_id = ? AND action = ?",
                String.class,
                runId,
                action);
        return new ObjectMapper().readValue(json, new TypeReference<Map<String, Object>>() {
        });
    }

    private List<String> pdfActionsFor(Long runId) {
        return jdbcTemplate.queryForList(
                "SELECT action FROM audit_logs WHERE entity_name = 'PAYROLL_RUN' AND entity_id = ? "
                        + "AND action IN ('PAYROLL_RUN_PDF_GENERATION_FINISHED','PAYROLL_RUN_COMPLETED') ORDER BY id",
                String.class,
                runId);
    }

    // ---- 1: successful generation ----

    @Test
    void successfulGenerationWritesFilesCompletesTheRunAndAuditsExactly() throws IOException {
        int month = 1;
        long emp1 = insertCandidate("SUC1", "001301010001");
        long emp2 = insertCandidate("SUC2", "001301010002");
        coverFullMonth(emp1, month, new BigDecimal("3000.00"));
        coverFullMonth(emp2, month, new BigDecimal("4000.00"));
        PayrollRun run = payrollRunService.createAndCalculate(month, YEAR, adminUserId);
        long versionBeforeGeneration = run.getVersion();

        PayrollPdfGenerationResult result = payrollPdfService.generatePdfs(run.getId(), adminUserId);

        assertThat(result.payrollRunId()).isEqualTo(run.getId());
        assertThat(result.finalStatus()).isEqualTo(PayrollRunStatus.COMPLETED);
        assertThat(result.generatedCount()).isEqualTo(2);
        assertThat(result.failedCount()).isZero();
        assertThat(result.skippedCount()).isZero();

        List<Payslip> payslips = payslipRepository.findByPayrollRunIdOrderByIdAsc(run.getId());
        assertThat(payslips).hasSize(2);
        for (Payslip payslip : payslips) {
            assertThat(payslip.getPdfStatus()).isEqualTo(PdfStatus.GENERATED);
            assertThat(payslip.getPdfPath()).isEqualTo("payroll-run-" + run.getId() + "/payslip-" + payslip.getId() + ".pdf");
            assertThat(Path.of(payslip.getPdfPath()).isAbsolute()).isFalse();
            Path file = storageRoot.resolve(payslip.getPdfPath());
            assertThat(Files.isRegularFile(file)).isTrue();
            byte[] bytes = Files.readAllBytes(file);
            assertThat(bytes.length).isGreaterThan(0);
            assertThat(new String(bytes, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        }

        PayrollRun reloaded = payrollRunRepository.findById(run.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PayrollRunStatus.COMPLETED);
        assertThat(reloaded.getVersion()).isGreaterThan(versionBeforeGeneration);

        assertThat(pdfActionsFor(run.getId())).containsExactly("PAYROLL_RUN_PDF_GENERATION_FINISHED", "PAYROLL_RUN_COMPLETED");

        Map<String, Object> finished = detailsAsMap(run.getId(), "PAYROLL_RUN_PDF_GENERATION_FINISHED");
        assertThat(finished.keySet())
                .containsExactlyInAnyOrder("payrollRunId", "periodMonth", "periodYear", "generatedCount", "failedCount", "skippedCount");
        assertThat(finished.get("generatedCount")).isEqualTo(2);
        assertThat(finished.get("failedCount")).isEqualTo(0);
        assertThat(finished.get("skippedCount")).isEqualTo(0);

        Map<String, Object> completed = detailsAsMap(run.getId(), "PAYROLL_RUN_COMPLETED");
        assertThat(completed.keySet()).containsExactlyInAnyOrder("payrollRunId", "periodMonth", "periodYear", "payslipCount");
        assertThat(completed.get("payslipCount")).isEqualTo(2);
    }

    // ---- 2: stored snapshot correctness ----

    @Test
    void generatedPdfReflectsTheStoredSnapshotNotCurrentEmployeeOrDepartmentData() throws IOException {
        int month = 2;
        long emp = insertCandidate("SNAP", "001302010001");
        coverFullMonth(emp, month, new BigDecimal("2500.00"));
        PayrollRun run = payrollRunService.createAndCalculate(month, YEAR, adminUserId);
        Payslip payslip = payslipRepository.findByPayrollRunIdOrderByIdAsc(run.getId()).get(0);
        String originalName = payslip.getEmployeeNameSnapshot();
        String originalDept = payslip.getDepartmentNameSnapshot();

        // The department row is a shared fixture that PayrollTestFixtures.cleanup() finds by exact
        // name match -- it must be restored to its original name before this test ends, or a
        // renamed leftover row would silently survive cleanup and collide with the next run.
        jdbcTemplate.update("UPDATE employees SET full_name = ? WHERE id = ?", "Changed Later Name", emp);
        jdbcTemplate.update("UPDATE departments SET name = ? WHERE id = ?", "Changed Later Department", departmentId);
        try {
            payrollPdfService.generatePdfs(run.getId(), adminUserId);

            Payslip reloaded = payslipRepository.findByPayrollRunIdOrderByIdAsc(run.getId()).get(0);
            byte[] bytes = Files.readAllBytes(storageRoot.resolve(reloaded.getPdfPath()));
            String text = extractText(bytes);

            assertThat(text).contains(originalName);
            assertThat(text).contains(originalDept);
            assertThat(text).doesNotContain("Changed Later Name");
            assertThat(text).doesNotContain("Changed Later Department");
        } finally {
            jdbcTemplate.update(
                    "UPDATE departments SET name = ? WHERE id = ?", PayrollTestFixtures.DEPARTMENT_NAME, departmentId);
        }
    }

    // ---- 3: partial failure ----

    @Test
    void partialFailureLeavesOneFailedOthersGeneratedAndRunCalculated() throws IOException {
        int month = 3;
        long empOk = insertCandidate("OK3", "001303010001");
        long empBad = insertCandidate("BAD3", "001303010002");
        coverFullMonth(empOk, month, new BigDecimal("1000.00"));
        coverFullMonth(empBad, month, new BigDecimal("2000.00"));
        PayrollRun run = payrollRunService.createAndCalculate(month, YEAR, adminUserId);
        String badEmployeeCode = PayrollTestFixtures.EMPLOYEE_CODE_PREFIX + "BAD3";
        controllableRenderer().failOnceFor(badEmployeeCode);

        PayrollPdfGenerationResult result = payrollPdfService.generatePdfs(run.getId(), adminUserId);

        assertThat(result.finalStatus()).isEqualTo(PayrollRunStatus.CALCULATED);
        assertThat(result.generatedCount()).isEqualTo(1);
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(result.skippedCount()).isZero();

        List<Payslip> payslips = payslipRepository.findByPayrollRunIdOrderByIdAsc(run.getId());
        Payslip failed = payslips.stream()
                .filter(p -> p.getEmployeeCodeSnapshot().equals(badEmployeeCode))
                .findFirst()
                .orElseThrow();
        Payslip ok = payslips.stream()
                .filter(p -> !p.getEmployeeCodeSnapshot().equals(badEmployeeCode))
                .findFirst()
                .orElseThrow();
        assertThat(failed.getPdfStatus()).isEqualTo(PdfStatus.FAILED);
        assertThat(failed.getPdfPath()).isNull();
        assertThat(ok.getPdfStatus()).isEqualTo(PdfStatus.GENERATED);
        assertThat(Files.isRegularFile(storageRoot.resolve(ok.getPdfPath()))).isTrue();

        PayrollRun reloaded = payrollRunRepository.findById(run.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PayrollRunStatus.CALCULATED);

        assertThat(pdfActionsFor(run.getId())).containsExactly("PAYROLL_RUN_PDF_GENERATION_FINISHED");

        Map<String, Object> finished = detailsAsMap(run.getId(), "PAYROLL_RUN_PDF_GENERATION_FINISHED");
        assertThat(finished.get("generatedCount")).isEqualTo(1);
        assertThat(finished.get("failedCount")).isEqualTo(1);
        assertThat(finished.get("skippedCount")).isEqualTo(0);
        String detailsJson = new ObjectMapper().writeValueAsString(finished);
        assertThat(detailsJson)
                .doesNotContain("1000.00")
                .doesNotContain("2000.00")
                .doesNotContain(badEmployeeCode)
                .doesNotContain("Simulated deterministic")
                .doesNotContain("payroll-run-");
    }

    // ---- 4: retry after partial failure ----

    @Test
    void retryAfterPartialFailureOnlyRegeneratesTheFailedPayslip() throws IOException, InterruptedException {
        int month = 4;
        long empOk = insertCandidate("OK4", "001304010001");
        long empBad = insertCandidate("BAD4", "001304010002");
        coverFullMonth(empOk, month, new BigDecimal("1500.00"));
        coverFullMonth(empBad, month, new BigDecimal("2500.00"));
        PayrollRun run = payrollRunService.createAndCalculate(month, YEAR, adminUserId);
        String badCode = PayrollTestFixtures.EMPLOYEE_CODE_PREFIX + "BAD4";
        controllableRenderer().failOnceFor(badCode);

        PayrollPdfGenerationResult first = payrollPdfService.generatePdfs(run.getId(), adminUserId);
        assertThat(first.finalStatus()).isEqualTo(PayrollRunStatus.CALCULATED);

        Payslip okAfterFirst = payslipRepository.findByPayrollRunIdOrderByIdAsc(run.getId()).stream()
                .filter(p -> !p.getEmployeeCodeSnapshot().equals(badCode))
                .findFirst()
                .orElseThrow();
        Path okFile = storageRoot.resolve(okAfterFirst.getPdfPath());
        byte[] okBytesBefore = Files.readAllBytes(okFile);
        FileTime okMtimeBefore = Files.getLastModifiedTime(okFile);

        Thread.sleep(50);
        PayrollPdfGenerationResult second = payrollPdfService.generatePdfs(run.getId(), adminUserId);

        assertThat(second.finalStatus()).isEqualTo(PayrollRunStatus.COMPLETED);
        assertThat(second.generatedCount()).isEqualTo(1);
        assertThat(second.failedCount()).isZero();
        assertThat(second.skippedCount()).isEqualTo(1);

        assertThat(Files.readAllBytes(okFile)).isEqualTo(okBytesBefore);
        assertThat(Files.getLastModifiedTime(okFile)).isEqualTo(okMtimeBefore);

        List<Payslip> finalPayslips = payslipRepository.findByPayrollRunIdOrderByIdAsc(run.getId());
        assertThat(finalPayslips).allMatch(p -> p.getPdfStatus() == PdfStatus.GENERATED);

        Integer completedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE entity_name = 'PAYROLL_RUN' AND entity_id = ? AND action = 'PAYROLL_RUN_COMPLETED'",
                Integer.class,
                run.getId());
        Integer finishedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE entity_name = 'PAYROLL_RUN' AND entity_id = ? AND action = 'PAYROLL_RUN_PDF_GENERATION_FINISHED'",
                Integer.class,
                run.getId());
        assertThat(completedCount).isEqualTo(1);
        assertThat(finishedCount).isEqualTo(2);
    }

    // ---- 5: existing GENERATED payslips skipped / run-state-only repair ----

    @Test
    void allAlreadyGeneratedPayslipsAreSkippedAndTheServiceOnlyRepairsTheRunState() throws IOException, InterruptedException {
        int month = 6;
        long emp1 = insertCandidate("REPAIR1", "001306010001");
        long emp2 = insertCandidate("REPAIR2", "001306010002");
        coverFullMonth(emp1, month, new BigDecimal("1000.00"));
        coverFullMonth(emp2, month, new BigDecimal("2000.00"));
        PayrollRun run = payrollRunService.createAndCalculate(month, YEAR, adminUserId);
        List<Payslip> payslips = payslipRepository.findByPayrollRunIdOrderByIdAsc(run.getId());

        Map<Long, byte[]> preExistingBytes = new HashMap<>();
        Map<Long, FileTime> preExistingMtimes = new HashMap<>();
        for (Payslip payslip : payslips) {
            String relativePath = "payroll-run-" + run.getId() + "/payslip-" + payslip.getId() + ".pdf";
            Path file = storageRoot.resolve(relativePath);
            Files.createDirectories(file.getParent());
            Files.write(file, ("%PDF-1.7 pre-existing " + payslip.getId()).getBytes(StandardCharsets.US_ASCII));
            jdbcTemplate.update(
                    "UPDATE payslips SET pdf_status = 'GENERATED', pdf_path = ? WHERE id = ?", relativePath, payslip.getId());
            preExistingBytes.put(payslip.getId(), Files.readAllBytes(file));
            preExistingMtimes.put(payslip.getId(), Files.getLastModifiedTime(file));
        }

        Thread.sleep(50);
        PayrollPdfGenerationResult result = payrollPdfService.generatePdfs(run.getId(), adminUserId);

        assertThat(result.finalStatus()).isEqualTo(PayrollRunStatus.COMPLETED);
        assertThat(result.generatedCount()).isZero();
        assertThat(result.failedCount()).isZero();
        assertThat(result.skippedCount()).isEqualTo(2);

        for (Payslip payslip : payslips) {
            String relativePath = "payroll-run-" + run.getId() + "/payslip-" + payslip.getId() + ".pdf";
            Path file = storageRoot.resolve(relativePath);
            assertThat(Files.readAllBytes(file)).isEqualTo(preExistingBytes.get(payslip.getId()));
            assertThat(Files.getLastModifiedTime(file)).isEqualTo(preExistingMtimes.get(payslip.getId()));
        }

        PayrollRun reloaded = payrollRunRepository.findById(run.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PayrollRunStatus.COMPLETED);
        assertThat(pdfActionsFor(run.getId())).containsExactly("PAYROLL_RUN_PDF_GENERATION_FINISHED", "PAYROLL_RUN_COMPLETED");
    }

    // ---- 6: COMPLETED idempotency ----

    @Test
    void completedRunIsIdempotentNoOp() throws IOException, InterruptedException {
        int month = 7;
        long emp = insertCandidate("IDEM", "001307010001");
        coverFullMonth(emp, month, new BigDecimal("1800.00"));
        PayrollRun run = payrollRunService.createAndCalculate(month, YEAR, adminUserId);
        PayrollPdfGenerationResult first = payrollPdfService.generatePdfs(run.getId(), adminUserId);
        assertThat(first.finalStatus()).isEqualTo(PayrollRunStatus.COMPLETED);

        Payslip payslip = payslipRepository.findByPayrollRunIdOrderByIdAsc(run.getId()).get(0);
        Path file = storageRoot.resolve(payslip.getPdfPath());
        byte[] bytesBefore = Files.readAllBytes(file);
        FileTime mtimeBefore = Files.getLastModifiedTime(file);
        long versionBefore = payrollRunRepository.findById(run.getId()).orElseThrow().getVersion();

        Thread.sleep(50);
        PayrollPdfGenerationResult second = payrollPdfService.generatePdfs(run.getId(), adminUserId);

        assertThat(second.finalStatus()).isEqualTo(PayrollRunStatus.COMPLETED);
        assertThat(second.generatedCount()).isZero();
        assertThat(second.failedCount()).isZero();
        assertThat(second.skippedCount()).isEqualTo(1);

        assertThat(Files.readAllBytes(file)).isEqualTo(bytesBefore);
        assertThat(Files.getLastModifiedTime(file)).isEqualTo(mtimeBefore);

        long versionAfter = payrollRunRepository.findById(run.getId()).orElseThrow().getVersion();
        assertThat(versionAfter).isEqualTo(versionBefore);

        Integer completedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE entity_name = 'PAYROLL_RUN' AND entity_id = ? AND action = 'PAYROLL_RUN_COMPLETED'",
                Integer.class,
                run.getId());
        Integer finishedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE entity_name = 'PAYROLL_RUN' AND entity_id = ? AND action = 'PAYROLL_RUN_PDF_GENERATION_FINISHED'",
                Integer.class,
                run.getId());
        assertThat(completedCount).isEqualTo(1);
        assertThat(finishedCount).isEqualTo(1);
    }

    // ---- 7: invalid state ----

    @Test
    void processingRunIsRejected() {
        int month = 8;
        long runId = PayrollTestFixtures.insertPayrollRunWithoutV13Columns(jdbcTemplate, month, YEAR);
        extraRunIds.add(runId);

        assertThatThrownBy(() -> payrollPdfService.generatePdfs(runId, adminUserId))
                .isInstanceOf(PayrollRunNotCalculatedException.class);

        String status = jdbcTemplate.queryForObject("SELECT status FROM payroll_runs WHERE id = ?", String.class, runId);
        assertThat(status).isEqualTo("PROCESSING");
        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE entity_name = 'PAYROLL_RUN' AND entity_id = ?", Integer.class, runId);
        assertThat(auditCount).isZero();
    }

    @Test
    void failedRunIsRejected() {
        int month = 9;
        assertThatThrownBy(() -> payrollRunService.createAndCalculate(month, YEAR, adminUserId))
                .isInstanceOf(PayrollRunFailedException.class);
        Long runId = jdbcTemplate.queryForObject(
                "SELECT id FROM payroll_runs WHERE period_month = ? AND period_year = ?", Long.class, month, YEAR);

        assertThatThrownBy(() -> payrollPdfService.generatePdfs(runId, adminUserId))
                .isInstanceOf(PayrollRunNotCalculatedException.class);

        String status = jdbcTemplate.queryForObject("SELECT status FROM payroll_runs WHERE id = ?", String.class, runId);
        assertThat(status).isEqualTo("FAILED");
        Integer pdfAuditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE entity_name = 'PAYROLL_RUN' AND entity_id = ? "
                        + "AND action IN ('PAYROLL_RUN_PDF_GENERATION_FINISHED','PAYROLL_RUN_COMPLETED')",
                Integer.class,
                runId);
        assertThat(pdfAuditCount).isZero();
    }

    // ---- 8: unknown run ----

    @Test
    void unknownRunIsRejected() {
        assertThatThrownBy(() -> payrollPdfService.generatePdfs(999_999_999L, adminUserId))
                .isInstanceOf(PayrollRunNotFoundException.class);
    }

    // ---- 9: zero-payslip CALCULATED run ----

    @Test
    void zeroPayslipCalculatedRunIsRejectedAndRemainsCalculated() {
        int month = 10;
        long runId = PayrollTestFixtures.insertPayrollRunWithCreatedAt(jdbcTemplate, month, YEAR, "CALCULATED", LocalDateTime.now());
        extraRunIds.add(runId);

        assertThatThrownBy(() -> payrollPdfService.generatePdfs(runId, adminUserId))
                .isInstanceOf(PayrollRunHasNoPayslipsException.class);

        String status = jdbcTemplate.queryForObject("SELECT status FROM payroll_runs WHERE id = ?", String.class, runId);
        assertThat(status).isEqualTo("CALCULATED");
        Integer completedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE entity_name = 'PAYROLL_RUN' AND entity_id = ? AND action = 'PAYROLL_RUN_COMPLETED'",
                Integer.class,
                runId);
        assertThat(completedCount).isZero();
    }

    // ---- 10: persistence failure escapes rather than being swallowed ----

    @Test
    void persistenceFailureAtFlushEscapesAndRollsBackRatherThanBeingSwallowed() throws IOException {
        int month = 11;
        long emp = insertCandidate("PERSISTFAIL", "001311010001");
        coverFullMonth(emp, month, new BigDecimal("1200.00"));
        PayrollRun run = payrollRunService.createAndCalculate(month, YEAR, adminUserId);

        payrollPdfService.setPreFlushHookForTesting(() -> {
            throw new DataIntegrityViolationException("Simulated persistence failure for a test.");
        });
        try {
            assertThatThrownBy(() -> payrollPdfService.generatePdfs(run.getId(), adminUserId))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            payrollPdfService.setPreFlushHookForTesting(() -> {
            });
        }

        PayrollRun reloaded = payrollRunRepository.findById(run.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PayrollRunStatus.CALCULATED);

        List<Payslip> payslips = payslipRepository.findByPayrollRunIdOrderByIdAsc(run.getId());
        assertThat(payslips).allMatch(p -> p.getPdfStatus() == PdfStatus.PENDING);

        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE entity_name = 'PAYROLL_RUN' AND entity_id = ? "
                        + "AND action IN ('PAYROLL_RUN_PDF_GENERATION_FINISHED','PAYROLL_RUN_COMPLETED')",
                Integer.class,
                run.getId());
        assertThat(auditCount).isZero();

        // The PDF file was already atomically moved into place before the failing flush --
        // Postgres cannot roll back a filesystem write. This is a harmless, deterministic orphan
        // (the DB still says PENDING, so a real retry would simply overwrite it); clean it up here
        // so it does not linger as a stray artifact from this test.
        Path orphan = storageRoot.resolve("payroll-run-" + run.getId() + "/payslip-" + payslips.get(0).getId() + ".pdf");
        Files.deleteIfExists(orphan);
        Path orphanDir = orphan.getParent();
        if (Files.isDirectory(orphanDir)) {
            try (var entries = Files.list(orphanDir)) {
                if (entries.findAny().isEmpty()) {
                    Files.delete(orphanDir);
                }
            }
        }
    }
}
