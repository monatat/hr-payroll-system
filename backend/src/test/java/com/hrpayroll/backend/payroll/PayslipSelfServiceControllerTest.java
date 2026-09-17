package com.hrpayroll.backend.payroll;

import static org.assertj.core.api.Assertions.assertThat;
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
 * Real-PostgreSQL controller/integration tests for {@code GET /api/employees/me/payslips}
 * (Phase 8B-2B). Identity comes only from {@code AuthenticatedUser.employeeId()} -- there is no
 * {employeeId} path/query/body parameter anywhere on this endpoint, so there is nothing for a
 * client to spoof.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PayslipSelfServiceControllerTest {

    private static final int YEAR = PayrollTestFixtures.CONTROLLER_TEST_PERIOD_YEAR + 3; // own period, distinct from every other controller test class

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    private long departmentId;
    private long ownEmployeeId;
    private String employeeToken;
    private String adminToken;
    private final List<Long> extraRunIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        extraRunIds.clear();
        PayrollTestFixtures.cleanup(jdbcTemplate);
        departmentId = PayrollTestFixtures.installDepartment(jdbcTemplate);
        long adminUserId = PayrollTestFixtures.installAdminUser(jdbcTemplate, passwordEncoder);
        ownEmployeeId = PayrollTestFixtures.insertEmployee(jdbcTemplate, departmentId, "SELF", "000901010001");
        long employeeUserId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, role, account_status, employee_id) "
                        + "VALUES ('phase8b2b.self-employee@test.local', ?, 'EMPLOYEE', 'ACTIVE', ?) RETURNING id",
                Long.class,
                passwordEncoder.encode("Phase8B2BSelfEmployeePass123!"),
                ownEmployeeId);

        adminToken = jwtService.generateAccessToken(
                new User(adminUserId, PayrollTestFixtures.ADMIN_EMAIL, "hash", UserRole.HR_ADMIN, AccountStatus.ACTIVE, null));
        employeeToken = jwtService.generateAccessToken(new User(
                employeeUserId,
                "phase8b2b.self-employee@test.local",
                "hash",
                UserRole.EMPLOYEE,
                AccountStatus.ACTIVE,
                ownEmployeeId));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM users WHERE email = ?", "phase8b2b.self-employee@test.local");
        PayrollTestFixtures.cleanup(jdbcTemplate, extraRunIds);
    }

    private long insertPayslipFor(long employeeId, String codeSuffix, int month, LocalDateTime createdAt) {
        long runId = PayrollTestFixtures.insertPayrollRunWithCreatedAt(jdbcTemplate, month, YEAR, "CALCULATED", createdAt);
        extraRunIds.add(runId);
        long payslipId = PayrollTestFixtures.insertPayslip(
                jdbcTemplate,
                runId,
                employeeId,
                PayrollTestFixtures.EMPLOYEE_CODE_PREFIX + codeSuffix,
                new BigDecimal("1000.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("1000.00"),
                new BigDecimal("110.00"),
                new BigDecimal("5.00"),
                new BigDecimal("2.00"),
                new BigDecimal("50.00"),
                new BigDecimal("167.00"),
                new BigDecimal("833.00"));
        // insertPayslip doesn't control the payslip's own created_at -- align it with the run's for
        // this test's ordering assertions by using the run's created_at directly via a follow-up update.
        jdbcTemplate.update("UPDATE payslips SET created_at = ? WHERE id = ?", createdAt, payslipId);
        return payslipId;
    }

    @Test
    void employeeReceivesOnlyTheirOwnPayslipsNewestCreatedFirst() throws Exception {
        long otherEmployeeId = PayrollTestFixtures.insertEmployee(jdbcTemplate, departmentId, "OTHER-SELF", "000901010002");

        long earlier = insertPayslipFor(ownEmployeeId, "SELF", 1, LocalDateTime.of(2026, 1, 1, 10, 0));
        long later = insertPayslipFor(ownEmployeeId, "SELF", 2, LocalDateTime.of(2026, 1, 1, 11, 0));
        insertPayslipFor(otherEmployeeId, "OTHER-SELF", 3, LocalDateTime.of(2026, 1, 1, 12, 0));

        mockMvc.perform(get("/api/employees/me/payslips").header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].id").value(later))
                .andExpect(jsonPath("$.content[1].id").value(earlier));
    }

    @Test
    void sameCreatedAtRowsUseIdDescAsDeterministicTiebreaker() throws Exception {
        LocalDateTime sameInstant = LocalDateTime.of(2026, 2, 1, 9, 0);
        long lower = insertPayslipFor(ownEmployeeId, "SELF", 4, sameInstant);
        long higher = insertPayslipFor(ownEmployeeId, "SELF", 5, sameInstant);

        mockMvc.perform(get("/api/employees/me/payslips").header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(higher))
                .andExpect(jsonPath("$.content[1].id").value(lower));
    }

    @Test
    void paginationWorks() throws Exception {
        insertPayslipFor(ownEmployeeId, "SELF", 6, LocalDateTime.of(2026, 3, 1, 1, 0));
        insertPayslipFor(ownEmployeeId, "SELF", 7, LocalDateTime.of(2026, 3, 1, 2, 0));
        insertPayslipFor(ownEmployeeId, "SELF", 8, LocalDateTime.of(2026, 3, 1, 3, 0));

        mockMvc.perform(get("/api/employees/me/payslips")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken)
                        .param("size", "2")
                        .param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.page.totalElements").value(3));
    }

    @Test
    void responseIncludesPeriodMonthAndPeriodYearFromTheAssociatedRunNotCreatedAt() throws Exception {
        // Period (month 1, YEAR) is deliberately unrelated to createdAt (June 2026) -- a payroll
        // run can be created well after (or before) the period it covers, so periodMonth/
        // periodYear must come from the payslip's associated PayrollRun, never from createdAt.
        insertPayslipFor(ownEmployeeId, "SELF", 1, LocalDateTime.of(2026, 6, 15, 10, 0));

        mockMvc.perform(get("/api/employees/me/payslips").header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].periodMonth").value(1))
                .andExpect(jsonPath("$.content[0].periodYear").value(YEAR));
    }

    @Test
    void responseContainsNoPdfPath() throws Exception {
        insertPayslipFor(ownEmployeeId, "SELF", 9, LocalDateTime.now());

        mockMvc.perform(get("/api/employees/me/payslips").header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].pdfPath").doesNotExist());
    }

    @Test
    void unauthenticatedReturns401() throws Exception {
        mockMvc.perform(get("/api/employees/me/payslips")).andExpect(status().isUnauthorized());
    }

    @Test
    void hrAdminReturns403() throws Exception {
        mockMvc.perform(get("/api/employees/me/payslips").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isForbidden());
    }
}
