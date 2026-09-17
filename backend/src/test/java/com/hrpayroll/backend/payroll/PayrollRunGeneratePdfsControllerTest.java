package com.hrpayroll.backend.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hrpayroll.backend.payroll.pdf.PayslipPdfGenerator;
import com.hrpayroll.backend.payroll.pdf.PayslipPdfRenderer;
import com.hrpayroll.backend.security.jwt.JwtService;
import com.hrpayroll.backend.support.ControllablePayslipPdfRenderer;
import com.hrpayroll.backend.support.PayrollTestFixtures;
import com.hrpayroll.backend.user.AccountStatus;
import com.hrpayroll.backend.user.User;
import com.hrpayroll.backend.user.UserRole;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Real-PostgreSQL controller/integration tests for {@code POST /api/payroll-runs/{id}/generate-pdfs}
 * (Phase 8C-2B2). Every PDF file is written under a per-class {@code @TempDir}, overridden via
 * {@code @DynamicPropertySource} -- never the real development {@code ./data/payroll-pdfs}
 * directory. Uses its own reserved period year, distinct from every other PDF-related test class.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PayrollRunGeneratePdfsControllerTest {

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

    private static final int YEAR = PayrollTestFixtures.PDF_TEST_PERIOD_YEAR_MIN + 1; // own reserved period

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private PayslipPdfRenderer payslipPdfRenderer;

    @Autowired
    private PayrollRunService payrollRunService;

    @Autowired
    private PayslipRepository payslipRepository;

    private long departmentId;
    private long adminUserId;
    private String adminToken;
    private String employeeToken;
    private final List<Long> extraRunIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        extraRunIds.clear();
        PayrollTestFixtures.cleanup(jdbcTemplate);
        departmentId = PayrollTestFixtures.installDepartment(jdbcTemplate);
        adminUserId = PayrollTestFixtures.installAdminUser(jdbcTemplate, passwordEncoder);
        long employeeId = PayrollTestFixtures.insertEmployee(jdbcTemplate, departmentId, "GENPDF", "001401010001");
        long employeeUserId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, role, account_status, employee_id) "
                        + "VALUES ('phase8c2b2.genpdf-employee@test.local', ?, 'EMPLOYEE', 'ACTIVE', ?) RETURNING id",
                Long.class,
                passwordEncoder.encode("Phase8C2B2GenPdfEmployeePass123!"),
                employeeId);

        adminToken = jwtService.generateAccessToken(
                new User(adminUserId, PayrollTestFixtures.ADMIN_EMAIL, "hash", UserRole.HR_ADMIN, AccountStatus.ACTIVE, null));
        employeeToken = jwtService.generateAccessToken(new User(
                employeeUserId,
                "phase8c2b2.genpdf-employee@test.local",
                "hash",
                UserRole.EMPLOYEE,
                AccountStatus.ACTIVE,
                employeeId));

        ((ControllablePayslipPdfRenderer) payslipPdfRenderer).reset();
    }

    @AfterEach
    void tearDown() {
        ((ControllablePayslipPdfRenderer) payslipPdfRenderer).reset();
        jdbcTemplate.update("DELETE FROM users WHERE email = ?", "phase8c2b2.genpdf-employee@test.local");
        PayrollTestFixtures.cleanup(jdbcTemplate, extraRunIds);
    }

    private long insertCandidate(String codeSuffix, String ic) {
        return PayrollTestFixtures.insertEmployee(
                jdbcTemplate, departmentId, codeSuffix, ic, LocalDate.of(1999, 1, 1), "ACTIVE");
    }

    private void coverFullMonth(long employeeId, int month, BigDecimal basePay) {
        LocalDate periodStart = LocalDate.of(YEAR, month, 1);
        PayrollTestFixtures.insertSalaryStructure(
                jdbcTemplate, employeeId, basePay, BigDecimal.ZERO, BigDecimal.ZERO, periodStart, null);
    }

    @Test
    void hrAdminSuccessfulGenerationReturns200WithExactCountsAndCompletesTheRun() throws Exception {
        int month = 1;
        long emp = insertCandidate("GENOK1", "001401020001");
        coverFullMonth(emp, month, new BigDecimal("3000.00"));
        PayrollRun run = createAndCalculate(month);

        mockMvc.perform(post("/api/payroll-runs/" + run.getId() + "/generate-pdfs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payrollRunId").value(run.getId()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.generatedCount").value(1))
                .andExpect(jsonPath("$.failedCount").value(0))
                .andExpect(jsonPath("$.skippedCount").value(0));

        List<Payslip> payslips = payslipRepository.findByPayrollRunIdOrderByIdAsc(run.getId());
        assertThat(payslips).hasSize(1);
        assertThat(payslips.get(0).getPdfStatus()).isEqualTo(PdfStatus.GENERATED);
        Path file = storageRoot.resolve(payslips.get(0).getPdfPath());
        assertThat(Files.isRegularFile(file)).isTrue();

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

    @Test
    void partialFailureReturns200NotErrorWithFailedCountAndNoLeakage() throws Exception {
        int month = 2;
        long empOk = insertCandidate("GENOK2", "001401020002");
        long empBad = insertCandidate("GENBAD2", "001401020003");
        coverFullMonth(empOk, month, new BigDecimal("1000.00"));
        coverFullMonth(empBad, month, new BigDecimal("2000.00"));
        PayrollRun run = createAndCalculate(month);
        String badCode = PayrollTestFixtures.EMPLOYEE_CODE_PREFIX + "GENBAD2";
        ((ControllablePayslipPdfRenderer) payslipPdfRenderer).failOnceFor(badCode);

        String responseBody = mockMvc.perform(post("/api/payroll-runs/" + run.getId() + "/generate-pdfs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CALCULATED"))
                .andExpect(jsonPath("$.generatedCount").value(1))
                .andExpect(jsonPath("$.failedCount").value(1))
                .andExpect(jsonPath("$.skippedCount").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(responseBody).doesNotContain("1000.00").doesNotContain("2000.00").doesNotContain(badCode).doesNotContain("payroll-run-");

        String status = jdbcTemplate.queryForObject("SELECT status FROM payroll_runs WHERE id = ?", String.class, run.getId());
        assertThat(status).isEqualTo("CALCULATED");
    }

    @Test
    void completedRunIdempotentCallReturns200WithAllSkippedAndNoNewAuditOrFileRewrite() throws Exception {
        int month = 3;
        long emp = insertCandidate("GENIDEM", "001401020004");
        coverFullMonth(emp, month, new BigDecimal("1800.00"));
        PayrollRun run = createAndCalculate(month);

        mockMvc.perform(post("/api/payroll-runs/" + run.getId() + "/generate-pdfs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        Payslip payslip = payslipRepository.findByPayrollRunIdOrderByIdAsc(run.getId()).get(0);
        Path file = storageRoot.resolve(payslip.getPdfPath());
        byte[] bytesBefore = Files.readAllBytes(file);
        java.nio.file.attribute.FileTime mtimeBefore = Files.getLastModifiedTime(file);
        Thread.sleep(50);

        mockMvc.perform(post("/api/payroll-runs/" + run.getId() + "/generate-pdfs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.generatedCount").value(0))
                .andExpect(jsonPath("$.failedCount").value(0))
                .andExpect(jsonPath("$.skippedCount").value(1));

        assertThat(Files.readAllBytes(file)).isEqualTo(bytesBefore);
        assertThat(Files.getLastModifiedTime(file)).isEqualTo(mtimeBefore);

        Integer completedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE entity_name = 'PAYROLL_RUN' AND entity_id = ? AND action = 'PAYROLL_RUN_COMPLETED'",
                Integer.class,
                run.getId());
        assertThat(completedCount).isEqualTo(1);
    }

    @Test
    void processingRunReturns409PayrollRunNotCalculated() throws Exception {
        int month = 4;
        long runId = PayrollTestFixtures.insertPayrollRunWithoutV13Columns(jdbcTemplate, month, YEAR);
        extraRunIds.add(runId);

        mockMvc.perform(post("/api/payroll-runs/" + runId + "/generate-pdfs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYROLL_RUN_NOT_CALCULATED"));
    }

    @Test
    void failedRunReturns409PayrollRunNotCalculated() throws Exception {
        int month = 5;
        // Zero eligible employees -> FAILED via the real service.
        mockMvc.perform(post("/api/payroll-runs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType("application/json")
                        .content("{\"month\":" + month + ",\"year\":" + YEAR + "}"))
                .andExpect(status().isUnprocessableEntity());
        Long runId = jdbcTemplate.queryForObject(
                "SELECT id FROM payroll_runs WHERE period_month = ? AND period_year = ?", Long.class, month, YEAR);

        mockMvc.perform(post("/api/payroll-runs/" + runId + "/generate-pdfs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYROLL_RUN_NOT_CALCULATED"));
    }

    @Test
    void zeroPayslipCalculatedRunReturns409PayrollRunHasNoPayslips() throws Exception {
        int month = 6;
        long runId = PayrollTestFixtures.insertPayrollRunWithCreatedAt(jdbcTemplate, month, YEAR, "CALCULATED", LocalDateTime.now());
        extraRunIds.add(runId);

        mockMvc.perform(post("/api/payroll-runs/" + runId + "/generate-pdfs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYROLL_RUN_HAS_NO_PAYSLIPS"));
    }

    @Test
    void unknownRunReturns404PayrollRunNotFound() throws Exception {
        mockMvc.perform(post("/api/payroll-runs/999999999/generate-pdfs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYROLL_RUN_NOT_FOUND"));
    }

    @Test
    void unauthenticatedReturns401() throws Exception {
        mockMvc.perform(post("/api/payroll-runs/1/generate-pdfs")).andExpect(status().isUnauthorized());
    }

    @Test
    void employeeReturns403() throws Exception {
        mockMvc.perform(post("/api/payroll-runs/1/generate-pdfs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken))
                .andExpect(status().isForbidden());
    }

    // ---- test-local helpers (avoid depending on the full create endpoint's JSON shape) ----

    private PayrollRun createAndCalculate(int month) {
        return payrollRunService.createAndCalculate(month, YEAR, adminUserId);
    }
}
