package com.hrpayroll.backend.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
 * Real-PostgreSQL CORS integration tests for {@code GET /api/payslips/{id}/pdf} (Phase 8D-2A1).
 * The download endpoint returns a server-generated safe filename via {@code Content-Disposition},
 * but browser JavaScript on the Vite origin cannot read that header unless it is listed in
 * {@code Access-Control-Expose-Headers}. Uses the actual configured allowed origin
 * ({@code app.cors.allowed-origin}, default {@code http://localhost:5173}) rather than a
 * hard-coded value, so this test never drifts from the real CORS configuration.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PayslipPdfDownloadCorsTest {

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

    private static final int YEAR = PayrollTestFixtures.PDF_TEST_PERIOD_YEAR_MIN + 3; // own reserved period
    private static final String DISALLOWED_ORIGIN = "http://evil.example.com";

    @Value("${app.cors.allowed-origin}")
    private String allowedOrigin;

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
    private String adminToken;

    @BeforeEach
    void setUp() {
        PayrollTestFixtures.cleanup(jdbcTemplate);
        departmentId = PayrollTestFixtures.installDepartment(jdbcTemplate);
        adminUserId = PayrollTestFixtures.installAdminUser(jdbcTemplate, passwordEncoder);
        adminToken = jwtService.generateAccessToken(
                new User(adminUserId, PayrollTestFixtures.ADMIN_EMAIL, "hash", UserRole.HR_ADMIN, AccountStatus.ACTIVE, null));
        ((ControllablePayslipPdfRenderer) payslipPdfRenderer).reset();
    }

    @AfterEach
    void tearDown() {
        ((ControllablePayslipPdfRenderer) payslipPdfRenderer).reset();
        PayrollTestFixtures.cleanup(jdbcTemplate);
    }

    /** Creates a CALCULATED run for one employee and generates its (single) payslip's PDF for real. */
    private Payslip createGeneratedPayslip(int month) {
        long employeeId = PayrollTestFixtures.insertEmployee(
                jdbcTemplate, departmentId, "CORS" + month, "00160102000" + month, LocalDate.of(1999, 1, 1), "ACTIVE");
        LocalDate periodStart = LocalDate.of(YEAR, month, 1);
        PayrollTestFixtures.insertSalaryStructure(
                jdbcTemplate, employeeId, new BigDecimal("2100.00"), BigDecimal.ZERO, BigDecimal.ZERO, periodStart, null);
        PayrollRun run = payrollRunService.createAndCalculate(month, YEAR, adminUserId);
        payrollPdfService.generatePdfs(run.getId(), adminUserId);
        return payslipRepository.findByPayrollRunIdOrderByIdAsc(run.getId()).get(0);
    }

    @Test
    void allowedOriginReceivesExposedContentDispositionHeaderAndCanStillDownload() throws Exception {
        Payslip payslip = createGeneratedPayslip(1);

        mockMvc.perform(get("/api/payslips/" + payslip.getId() + "/pdf")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .header(HttpHeaders.ORIGIN, allowedOrigin))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, allowedOrigin))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "Content-Disposition"))
                .andExpect(header().string(
                        HttpHeaders.CONTENT_DISPOSITION, org.hamcrest.Matchers.startsWith("attachment;")))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/pdf"));
    }

    @Test
    void exposedHeadersNeverUsesWildcardAndNeverExposesAuthorization() throws Exception {
        Payslip payslip = createGeneratedPayslip(2);

        var result = mockMvc.perform(get("/api/payslips/" + payslip.getId() + "/pdf")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .header(HttpHeaders.ORIGIN, allowedOrigin))
                .andExpect(status().isOk())
                .andReturn();

        String exposeHeaders = result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS);
        assertThat(exposeHeaders).isNotNull();
        assertThat(exposeHeaders).doesNotContain("*");
        assertThat(exposeHeaders).doesNotContainIgnoringCase("Authorization");
    }

    @Test
    void disallowedOriginReceivesNoAccessControlAllowOriginHeader() throws Exception {
        Payslip payslip = createGeneratedPayslip(3);

        mockMvc.perform(get("/api/payslips/" + payslip.getId() + "/pdf")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .header(HttpHeaders.ORIGIN, DISALLOWED_ORIGIN))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
}
