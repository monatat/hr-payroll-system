package com.hrpayroll.backend.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
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
 * Real-PostgreSQL controller/integration tests for {@code GET /api/payslips/{id}/pdf} (Phase
 * 8C-2B2). Every PDF file is written under a per-class {@code @TempDir}, overridden via
 * {@code @DynamicPropertySource} -- never the real development {@code ./data/payroll-pdfs}
 * directory. Uses its own reserved period year, distinct from every other PDF-related test class.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PayslipPdfDownloadControllerTest {

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

    private static final int YEAR = PayrollTestFixtures.PDF_TEST_PERIOD_YEAR_MIN + 2; // own reserved period

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
    private PayrollPdfService payrollPdfService;

    @Autowired
    private PayslipRepository payslipRepository;

    private long departmentId;
    private long adminUserId;
    private long ownEmployeeId;
    private String adminToken;
    private String employeeToken;
    private final List<Long> extraRunIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        extraRunIds.clear();
        PayrollTestFixtures.cleanup(jdbcTemplate);
        departmentId = PayrollTestFixtures.installDepartment(jdbcTemplate);
        adminUserId = PayrollTestFixtures.installAdminUser(jdbcTemplate, passwordEncoder);
        // INACTIVE by default so this fixture employee is never an accidental payroll candidate
        // for another test's period in this same class (every test shares the YEAR constant but
        // uses a different month) -- only the "owning EMPLOYEE" test flips it to ACTIVE, scoped to
        // that one test.
        ownEmployeeId = PayrollTestFixtures.insertEmployee(
                jdbcTemplate, departmentId, "DLOWN", "001501010001", LocalDate.of(1999, 1, 1), "INACTIVE");
        long employeeUserId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, role, account_status, employee_id) "
                        + "VALUES ('phase8c2b2.dl-employee@test.local', ?, 'EMPLOYEE', 'ACTIVE', ?) RETURNING id",
                Long.class,
                passwordEncoder.encode("Phase8C2B2DownloadEmployeePass123!"),
                ownEmployeeId);

        adminToken = jwtService.generateAccessToken(
                new User(adminUserId, PayrollTestFixtures.ADMIN_EMAIL, "hash", UserRole.HR_ADMIN, AccountStatus.ACTIVE, null));
        employeeToken = jwtService.generateAccessToken(new User(
                employeeUserId, "phase8c2b2.dl-employee@test.local", "hash", UserRole.EMPLOYEE, AccountStatus.ACTIVE, ownEmployeeId));

        ((ControllablePayslipPdfRenderer) payslipPdfRenderer).reset();
    }

    @AfterEach
    void tearDown() {
        ((ControllablePayslipPdfRenderer) payslipPdfRenderer).reset();
        jdbcTemplate.update("DELETE FROM users WHERE email = ?", "phase8c2b2.dl-employee@test.local");
        PayrollTestFixtures.cleanup(jdbcTemplate, extraRunIds);
    }

    /** Hired well before every period this class ever tests, so it is always a valid candidate once given salary coverage. */
    private long insertCandidate(String codeSuffix, String ic) {
        return PayrollTestFixtures.insertEmployee(jdbcTemplate, departmentId, codeSuffix, ic, LocalDate.of(1999, 1, 1), "ACTIVE");
    }

    private void coverFullMonth(long employeeId, int month, BigDecimal basePay) {
        LocalDate periodStart = LocalDate.of(YEAR, month, 1);
        PayrollTestFixtures.insertSalaryStructure(
                jdbcTemplate, employeeId, basePay, BigDecimal.ZERO, BigDecimal.ZERO, periodStart, null);
    }

    /** Creates a CALCULATED run for one employee and generates its (single) payslip's PDF for real. */
    private Payslip createGeneratedPayslip(long employeeId, int month) {
        coverFullMonth(employeeId, month, new BigDecimal("2200.00"));
        PayrollRun run = payrollRunService.createAndCalculate(month, YEAR, adminUserId);
        payrollPdfService.generatePdfs(run.getId(), adminUserId);
        return payslipRepository.findByPayrollRunIdOrderByIdAsc(run.getId()).get(0);
    }

    private static void createSymbolicLinkOrAssume(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            Assumptions.assumeTrue(
                    false,
                    "Symbolic link creation is not permitted on this OS/environment ("
                            + e.getClass().getSimpleName() + ") -- skipping this symlink-specific test only.");
        }
    }

    // ---- 1: HR_ADMIN downloads any GENERATED payslip ----

    @Test
    void hrAdminDownloadsAnyGeneratedPayslip() throws Exception {
        int month = 1;
        long otherEmployee = insertCandidate("DLOTHER1", "001501020001");
        Payslip payslip = createGeneratedPayslip(otherEmployee, month);

        byte[] body = mockMvc.perform(get("/api/payslips/" + payslip.getId() + "/pdf")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/pdf"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, org.hamcrest.Matchers.startsWith("attachment;")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("private")))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        assertThat(new String(body, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        try (PDDocument document = Loader.loadPDF(body)) {
            assertThat(document.getNumberOfPages()).isGreaterThan(0);
        }
    }

    @Test
    void contentLengthHeaderMatchesExactBodyLength() throws Exception {
        int month = 2;
        long otherEmployee = insertCandidate("DLOTHER2", "001501020002");
        Payslip payslip = createGeneratedPayslip(otherEmployee, month);

        var result = mockMvc.perform(get("/api/payslips/" + payslip.getId() + "/pdf")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        int declaredLength = Integer.parseInt(result.getResponse().getHeader(HttpHeaders.CONTENT_LENGTH));
        byte[] body = result.getResponse().getContentAsByteArray();
        assertThat(declaredLength).isEqualTo(body.length);
    }

    // ---- 2: owning EMPLOYEE downloads own ----

    @Test
    void owningEmployeeDownloadsTheirOwnGeneratedPayslip() throws Exception {
        int month = 3;
        jdbcTemplate.update("UPDATE employees SET employment_status = 'ACTIVE' WHERE id = ?", ownEmployeeId);
        Payslip payslip = createGeneratedPayslip(ownEmployeeId, month);

        byte[] body = mockMvc.perform(get("/api/payslips/" + payslip.getId() + "/pdf")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/pdf"))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        assertThat(new String(body, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    // ---- 3: non-owning EMPLOYEE ----

    @Test
    void nonOwningEmployeeReceivesTheSame404AsUnknownIdNeverForbidden() throws Exception {
        int month = 4;
        long otherEmployee = insertCandidate("DLOTHER3", "001501020003");
        Payslip payslip = createGeneratedPayslip(otherEmployee, month);

        mockMvc.perform(get("/api/payslips/" + payslip.getId() + "/pdf")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYSLIP_NOT_FOUND"));
    }

    // ---- 4: unknown payslip ----

    @Test
    void unknownPayslipReturns404() throws Exception {
        mockMvc.perform(get("/api/payslips/999999999/pdf").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYSLIP_NOT_FOUND"));
    }

    // ---- 5/6: PENDING / FAILED ----

    @Test
    void pendingPayslipReturns409PayslipPdfNotAvailable() throws Exception {
        int month = 5;
        long employee = insertCandidate("DLPEND", "001501020004");
        coverFullMonth(employee, month, new BigDecimal("1900.00"));
        PayrollRun run = payrollRunService.createAndCalculate(month, YEAR, adminUserId);
        Payslip payslip = payslipRepository.findByPayrollRunIdOrderByIdAsc(run.getId()).get(0);

        mockMvc.perform(get("/api/payslips/" + payslip.getId() + "/pdf").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYSLIP_PDF_NOT_AVAILABLE"));
    }

    @Test
    void failedPdfPayslipReturns409PayslipPdfNotAvailable() throws Exception {
        int month = 6;
        long employee = insertCandidate("DLFAIL", "001501020005");
        coverFullMonth(employee, month, new BigDecimal("1700.00"));
        PayrollRun run = payrollRunService.createAndCalculate(month, YEAR, adminUserId);
        String employeeCode = PayrollTestFixtures.EMPLOYEE_CODE_PREFIX + "DLFAIL";
        ((ControllablePayslipPdfRenderer) payslipPdfRenderer).failOnceFor(employeeCode);
        payrollPdfService.generatePdfs(run.getId(), adminUserId);
        Payslip payslip = payslipRepository.findByPayrollRunIdOrderByIdAsc(run.getId()).get(0);
        assertThat(payslip.getPdfStatus()).isEqualTo(PdfStatus.FAILED);

        mockMvc.perform(get("/api/payslips/" + payslip.getId() + "/pdf").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYSLIP_PDF_NOT_AVAILABLE"));
    }

    // ---- 7: GENERATED but physical file missing ----

    @Test
    void generatedWithMissingPhysicalFileReturns500WithoutLeakingAnyPath() throws Exception {
        int month = 7;
        long employee = insertCandidate("DLMISS", "001501020006");
        Payslip payslip = createGeneratedPayslip(employee, month);
        Path file = storageRoot.resolve(payslip.getPdfPath());
        Files.delete(file);

        String body = mockMvc.perform(get("/api/payslips/" + payslip.getId() + "/pdf").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("PAYSLIP_PDF_FILE_MISSING"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).doesNotContain(storageRoot.toString()).doesNotContain(payslip.getPdfPath());
    }

    // ---- 8: GENERATED with invalid/traversal/symlink path ----

    @Test
    void generatedWithTraversalStoredPathReturns500StorageUnavailableWithoutLeakingAnyPath() throws Exception {
        int month = 8;
        long employee = insertCandidate("DLTRAV", "001501020007");
        Payslip payslip = createGeneratedPayslip(employee, month);
        jdbcTemplate.update("UPDATE payslips SET pdf_path = ? WHERE id = ?", "../evil.pdf", payslip.getId());

        String body = mockMvc.perform(get("/api/payslips/" + payslip.getId() + "/pdf").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("PAYROLL_PDF_STORAGE_UNAVAILABLE"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).doesNotContain(storageRoot.toString()).doesNotContain("evil.pdf");
    }

    @Test
    void generatedWithSymlinkStoredPathReturns500StorageUnavailable() throws Exception {
        int month = 9;
        long employee = insertCandidate("DLSYM", "001501020008");
        Payslip payslip = createGeneratedPayslip(employee, month);
        Path outsideFile = storageRoot.getParent().resolve("outside-secret-" + payslip.getId() + ".pdf");
        Files.writeString(outsideFile, "secret", StandardCharsets.US_ASCII);
        Path linkPath = storageRoot.resolve("payroll-run-symlink-" + payslip.getId() + ".pdf");
        createSymbolicLinkOrAssume(linkPath, outsideFile);
        String relativeLinkPath = "payroll-run-symlink-" + payslip.getId() + ".pdf";
        jdbcTemplate.update("UPDATE payslips SET pdf_path = ? WHERE id = ?", relativeLinkPath, payslip.getId());

        String body = mockMvc.perform(get("/api/payslips/" + payslip.getId() + "/pdf").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("PAYROLL_PDF_STORAGE_UNAVAILABLE"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).doesNotContain(outsideFile.toString());
        Files.deleteIfExists(outsideFile);
    }

    // ---- 9: filename sanitization ----

    @Test
    void filenameSanitizationPreventsHeaderOrPathInjection() throws Exception {
        int month = 10;
        long employee = insertCandidate("DLSANI", "001501020009");
        Payslip payslip = createGeneratedPayslip(employee, month);
        String unsafeCode = "BAD\"CODE\r\nX;/\\..";
        jdbcTemplate.update("UPDATE payslips SET employee_code_snapshot = ? WHERE id = ?", unsafeCode, payslip.getId());

        var result = mockMvc.perform(get("/api/payslips/" + payslip.getId() + "/pdf").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        String disposition = result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(disposition).isNotBlank();
        assertThat(disposition).doesNotContain("\r").doesNotContain("\n").doesNotContain(";/");
        assertThat(disposition).matches("attachment; filename=\"payslip-\\d{4}-\\d{2}-[A-Za-z0-9_-]+\\.pdf\"");
    }

    // ---- 10: unauthenticated ----

    @Test
    void unauthenticatedReturns401() throws Exception {
        mockMvc.perform(get("/api/payslips/1/pdf")).andExpect(status().isUnauthorized());
    }
}
