package com.hrpayroll.backend.payroll;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hrpayroll.backend.security.jwt.JwtService;
import com.hrpayroll.backend.support.PayrollTestFixtures;
import com.hrpayroll.backend.user.AccountStatus;
import com.hrpayroll.backend.user.User;
import com.hrpayroll.backend.user.UserRole;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Real-PostgreSQL controller/integration tests for the HR_ADMIN payslip
 * detail endpoint (Phase 8B-2A). HR_ADMIN-only in this sub-phase --
 * EMPLOYEE self-service ownership rules are deferred to Phase 8B-2B.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PayslipControllerTest {

    private static final int YEAR = PayrollTestFixtures.CONTROLLER_TEST_PERIOD_YEAR + 2; // own period, distinct from PayrollRunControllerTest

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    private long departmentId;
    private String adminToken;
    private String employeeToken;
    private final List<Long> extraRunIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        extraRunIds.clear();
        PayrollTestFixtures.cleanup(jdbcTemplate);
        departmentId = PayrollTestFixtures.installDepartment(jdbcTemplate);
        long adminUserId = PayrollTestFixtures.installAdminUser(jdbcTemplate, passwordEncoder);
        long employeeId = PayrollTestFixtures.insertEmployee(jdbcTemplate, departmentId, "PAYSLIP", "000801010001");
        long employeeUserId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, role, account_status, employee_id) "
                        + "VALUES ('phase8b2a.payslip-employee@test.local', ?, 'EMPLOYEE', 'ACTIVE', ?) RETURNING id",
                Long.class,
                passwordEncoder.encode("Phase8B2APayslipEmployeePass123!"),
                employeeId);

        adminToken = jwtService.generateAccessToken(
                new User(adminUserId, PayrollTestFixtures.ADMIN_EMAIL, "hash", UserRole.HR_ADMIN, AccountStatus.ACTIVE, null));
        employeeToken = jwtService.generateAccessToken(new User(
                employeeUserId,
                "phase8b2a.payslip-employee@test.local",
                "hash",
                UserRole.EMPLOYEE,
                AccountStatus.ACTIVE,
                employeeId));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM users WHERE email = ?", "phase8b2a.payslip-employee@test.local");
        PayrollTestFixtures.cleanup(jdbcTemplate, extraRunIds);
    }

    private long insertRunAndPayslip() {
        long runId = PayrollTestFixtures.insertPayrollRunWithCreatedAt(jdbcTemplate, 1, YEAR, "CALCULATED", LocalDateTime.now());
        extraRunIds.add(runId);
        long employeeId = PayrollTestFixtures.insertEmployee(jdbcTemplate, departmentId, "SLIP", "000801010002");
        return PayrollTestFixtures.insertPayslip(
                jdbcTemplate,
                runId,
                employeeId,
                PayrollTestFixtures.EMPLOYEE_CODE_PREFIX + "SLIP",
                new BigDecimal("5000.00"),
                new BigDecimal("500.00"),
                new BigDecimal("200.00"),
                new BigDecimal("5700.00"),
                new BigDecimal("627.00"),
                new BigDecimal("28.50"),
                new BigDecimal("11.40"),
                new BigDecimal("285.00"),
                new BigDecimal("951.90"),
                new BigDecimal("4748.10"));
    }

    @Test
    void hrAdminReceivesExactHistoricalSnapshotAndMonetaryBreakdownWithoutPdfPath() throws Exception {
        long payslipId = insertRunAndPayslip();

        mockMvc.perform(get("/api/payslips/" + payslipId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeCodeSnapshot").value(PayrollTestFixtures.EMPLOYEE_CODE_PREFIX + "SLIP"))
                .andExpect(jsonPath("$.employeeNameSnapshot").value("Phase8 Test Employee"))
                .andExpect(jsonPath("$.departmentNameSnapshot").value(PayrollTestFixtures.DEPARTMENT_NAME))
                .andExpect(jsonPath("$.jobTitleSnapshot").value("Engineer"))
                .andExpect(jsonPath("$.basePay").value(5000.00))
                .andExpect(jsonPath("$.grossPay").value(5700.00))
                .andExpect(jsonPath("$.epfDeduction").value(627.00))
                .andExpect(jsonPath("$.socsoDeduction").value(28.50))
                .andExpect(jsonPath("$.eisDeduction").value(11.40))
                .andExpect(jsonPath("$.incomeTax").value(285.00))
                .andExpect(jsonPath("$.totalDeductions").value(951.90))
                .andExpect(jsonPath("$.netPay").value(4748.10))
                .andExpect(jsonPath("$.pdfStatus").value("PENDING"))
                .andExpect(jsonPath("$.pdfPath").doesNotExist());
    }

    @Test
    void unknownPayslipIdReturns404WithStableCode() throws Exception {
        mockMvc.perform(get("/api/payslips/999999999").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYSLIP_NOT_FOUND"));
    }

    @Test
    void unauthenticatedReturns401() throws Exception {
        mockMvc.perform(get("/api/payslips/1")).andExpect(status().isUnauthorized());
    }

    @Test
    void employeeReturns403() throws Exception {
        long payslipId = insertRunAndPayslip();

        mockMvc.perform(
                        get("/api/payslips/" + payslipId).header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken))
                .andExpect(status().isForbidden());
    }
}
