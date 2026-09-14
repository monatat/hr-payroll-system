package com.hrpayroll.backend.leave;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hrpayroll.backend.security.jwt.JwtService;
import com.hrpayroll.backend.support.LeaveTestFixtures;
import com.hrpayroll.backend.user.AccountStatus;
import com.hrpayroll.backend.user.User;
import com.hrpayroll.backend.user.UserRole;
import java.math.BigDecimal;
import java.time.LocalDate;
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
class LeaveRequestSelfServiceControllerTest {

    private static final LocalDate MON = LocalDate.of(2026, 9, 7);
    private static final LocalDate FRI = LocalDate.of(2026, 9, 11);

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
    private long annualLeaveTypeId;
    private String adminToken;
    private String employeeToken;

    @BeforeEach
    void setUp() {
        LeaveTestFixtures.cleanup(jdbcTemplate);
        long departmentId = LeaveTestFixtures.installDepartment(jdbcTemplate);
        long adminUserId = LeaveTestFixtures.installAdminUser(jdbcTemplate, passwordEncoder);

        long[] employeeAndUser = LeaveTestFixtures.installEmployeeWithUser(
                jdbcTemplate, passwordEncoder, departmentId, "MEREQ", "770101-03-0003");
        employeeId = employeeAndUser[0];
        long employeeUserId = employeeAndUser[1];

        long[] otherEmployeeAndUser = LeaveTestFixtures.installOtherEmployeeWithUser(
                jdbcTemplate, passwordEncoder, departmentId, "MEREQOTHER", "770101-03-0004");
        otherEmployeeId = otherEmployeeAndUser[0];

        annualLeaveTypeId = LeaveTestFixtures.findLeaveTypeIdByName(jdbcTemplate, LeaveTestFixtures.ANNUAL_LEAVE_TYPE_NAME);

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
        mockMvc.perform(get("/api/leave-requests/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void hrAdminCannotUseTheSelfServiceEndpoint() throws Exception {
        mockMvc.perform(get("/api/leave-requests/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void employeeListsOnlyTheirOwnRequests() throws Exception {
        LeaveTestFixtures.insertLeaveRequest(
                jdbcTemplate, employeeId, annualLeaveTypeId, MON, FRI, new BigDecimal("5.0"), "PENDING");
        LeaveTestFixtures.insertLeaveRequest(
                jdbcTemplate, otherEmployeeId, annualLeaveTypeId, MON, FRI, new BigDecimal("5.0"), "PENDING");

        mockMvc.perform(get("/api/leave-requests/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].employeeId").value(employeeId));
    }
}
