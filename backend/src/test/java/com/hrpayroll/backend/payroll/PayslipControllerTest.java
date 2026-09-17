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
 * Real-PostgreSQL controller/integration tests for {@code GET /api/payslips/{id}} (Phase 8B-2A
 * created it HR_ADMIN-only; Phase 8B-2B extends it with EMPLOYEE ownership: an owning employee may
 * read their own payslip, a non-owning employee gets the same 404 PAYSLIP_NOT_FOUND as an unknown
 * id -- never 403, which would reveal the record's existence to someone who shouldn't know about it).
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
    private long ownEmployeeId;
    private String adminToken;
    private String employeeToken;
    private final List<Long> extraRunIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        extraRunIds.clear();
        PayrollTestFixtures.cleanup(jdbcTemplate);
        departmentId = PayrollTestFixtures.installDepartment(jdbcTemplate);
        long adminUserId = PayrollTestFixtures.installAdminUser(jdbcTemplate, passwordEncoder);
        ownEmployeeId = PayrollTestFixtures.insertEmployee(jdbcTemplate, departmentId, "PAYSLIP", "000801010001");
        long employeeUserId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, role, account_status, employee_id) "
                        + "VALUES ('phase8b2a.payslip-employee@test.local', ?, 'EMPLOYEE', 'ACTIVE', ?) RETURNING id",
                Long.class,
                passwordEncoder.encode("Phase8B2APayslipEmployeePass123!"),
                ownEmployeeId);

        adminToken = jwtService.generateAccessToken(
                new User(adminUserId, PayrollTestFixtures.ADMIN_EMAIL, "hash", UserRole.HR_ADMIN, AccountStatus.ACTIVE, null));
        employeeToken = jwtService.generateAccessToken(new User(
                employeeUserId,
                "phase8b2a.payslip-employee@test.local",
                "hash",
                UserRole.EMPLOYEE,
                AccountStatus.ACTIVE,
                ownEmployeeId));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM users WHERE email = ?", "phase8b2a.payslip-employee@test.local");
        PayrollTestFixtures.cleanup(jdbcTemplate, extraRunIds);
    }

    private long insertPayslipFor(long employeeId, String codeSuffix) {
        long runId = PayrollTestFixtures.insertPayrollRunWithCreatedAt(jdbcTemplate, 1, YEAR, "CALCULATED", LocalDateTime.now());
        extraRunIds.add(runId);
        return PayrollTestFixtures.insertPayslip(
                jdbcTemplate,
                runId,
                employeeId,
                PayrollTestFixtures.EMPLOYEE_CODE_PREFIX + codeSuffix,
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

    /** A payslip belonging to a DIFFERENT employee than the one behind employeeToken. */
    private long insertRunAndPayslip() {
        long otherEmployeeId = PayrollTestFixtures.insertEmployee(jdbcTemplate, departmentId, "SLIP", "000801010002");
        return insertPayslipFor(otherEmployeeId, "SLIP");
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
                .andExpect(jsonPath("$.pdfPath").doesNotExist())
                // periodMonth/periodYear must come from the associated PayrollRun (month 1, YEAR
                // here), never from createdAt (LocalDateTime.now() -- a different month/year).
                .andExpect(jsonPath("$.periodMonth").value(1))
                .andExpect(jsonPath("$.periodYear").value(YEAR));
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
    void owningEmployeeCanReadTheirOwnPayslip() throws Exception {
        long payslipId = insertPayslipFor(ownEmployeeId, "OWN");

        mockMvc.perform(get("/api/payslips/" + payslipId).header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeCodeSnapshot").value(PayrollTestFixtures.EMPLOYEE_CODE_PREFIX + "OWN"))
                .andExpect(jsonPath("$.netPay").value(4748.10))
                .andExpect(jsonPath("$.pdfPath").doesNotExist())
                .andExpect(jsonPath("$.periodMonth").value(1))
                .andExpect(jsonPath("$.periodYear").value(YEAR));
    }

    @Test
    void differentEmployeeReceivesTheSameNotFoundAsAnUnknownIdNeverForbidden() throws Exception {
        long payslipId = insertRunAndPayslip(); // belongs to a different employee than employeeToken

        mockMvc.perform(get("/api/payslips/" + payslipId).header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYSLIP_NOT_FOUND"));
    }

    @Test
    void hrAdminCanStillReadAnyPayslipRegardlessOfOwnership() throws Exception {
        long payslipId = insertRunAndPayslip();

        mockMvc.perform(get("/api/payslips/" + payslipId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeCodeSnapshot").value(PayrollTestFixtures.EMPLOYEE_CODE_PREFIX + "SLIP"));
    }
}
