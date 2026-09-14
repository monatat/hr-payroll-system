package com.hrpayroll.backend.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hrpayroll.backend.security.jwt.JwtService;
import com.hrpayroll.backend.support.LeaveTestFixtures;
import com.hrpayroll.backend.user.AccountStatus;
import com.hrpayroll.backend.user.User;
import com.hrpayroll.backend.user.UserRole;
import java.math.BigDecimal;
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

@SpringBootTest
@AutoConfigureMockMvc
class LeaveBalanceSelfServiceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    private long employeeId;
    private long otherEmployeeId;
    private String adminToken;
    private String employeeToken;

    @BeforeEach
    void setUp() {
        LeaveTestFixtures.cleanup(jdbcTemplate);
        long departmentId = LeaveTestFixtures.installDepartment(jdbcTemplate);
        long adminUserId = LeaveTestFixtures.installAdminUser(jdbcTemplate, passwordEncoder);

        long[] employeeAndUser =
                LeaveTestFixtures.installEmployeeWithUser(jdbcTemplate, passwordEncoder, departmentId, "SELF", "770101-02-0002");
        employeeId = employeeAndUser[0];
        long employeeUserId = employeeAndUser[1];

        long[] otherEmployeeAndUser = LeaveTestFixtures.installOtherEmployeeWithUser(
                jdbcTemplate, passwordEncoder, departmentId, "OTHER", "770101-02-0003");
        otherEmployeeId = otherEmployeeAndUser[0];

        adminToken = jwtService.generateAccessToken(new User(
                adminUserId, LeaveTestFixtures.ADMIN_EMAIL, "hash", UserRole.HR_ADMIN, AccountStatus.ACTIVE, null));
        employeeToken = jwtService.generateAccessToken(new User(
                employeeUserId, LeaveTestFixtures.EMPLOYEE_EMAIL, "hash", UserRole.EMPLOYEE, AccountStatus.ACTIVE, employeeId));
    }

    @AfterEach
    void tearDown() {
        LeaveTestFixtures.cleanup(jdbcTemplate);
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/leave-balances/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void hrAdminCannotUseTheSelfServiceEndpoint() throws Exception {
        // Existing project policy (EmployeeSelfServiceControllerTest,
        // SalaryStructureSelfServiceControllerTest) is strict role
        // separation via @PreAuthorize -- HR_ADMIN never gets EMPLOYEE-only
        // routes, so this must also be 403.
        mockMvc.perform(get("/api/leave-balances/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void employeeCanReadOwnBalances() throws Exception {
        long leaveTypeId = LeaveTestFixtures.findLeaveTypeIdByName(jdbcTemplate, LeaveTestFixtures.SICK_LEAVE_TYPE_NAME);
        LeaveTestFixtures.insertLeaveBalance(
                jdbcTemplate, employeeId, leaveTypeId, 2026, new BigDecimal("14.0"), new BigDecimal("0.0"));

        mockMvc.perform(get("/api/leave-balances/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].employeeId").value(employeeId))
                .andExpect(jsonPath("$[0].leaveTypeName").value("Sick Leave"))
                .andExpect(jsonPath("$[0].remainingDays").value(14.0));
    }

    @Test
    void employeeNeverSeesAnotherEmployeesBalances() throws Exception {
        long leaveTypeId = LeaveTestFixtures.findLeaveTypeIdByName(jdbcTemplate, LeaveTestFixtures.ANNUAL_LEAVE_TYPE_NAME);
        LeaveTestFixtures.insertLeaveBalance(
                jdbcTemplate, employeeId, leaveTypeId, 2026, new BigDecimal("14.0"), new BigDecimal("1.0"));
        LeaveTestFixtures.insertLeaveBalance(
                jdbcTemplate, otherEmployeeId, leaveTypeId, 2026, new BigDecimal("14.0"), new BigDecimal("9.0"));

        String body = mockMvc.perform(get("/api/leave-balances/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).contains("\"usedDays\":1.0").doesNotContain("\"usedDays\":9.0");
    }
}
