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
class LeaveBalanceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    private long employeeId;
    private String adminToken;
    private String employeeToken;

    @BeforeEach
    void setUp() {
        LeaveTestFixtures.cleanup(jdbcTemplate);
        long departmentId = LeaveTestFixtures.installDepartment(jdbcTemplate);
        long adminUserId = LeaveTestFixtures.installAdminUser(jdbcTemplate, passwordEncoder);
        employeeId = LeaveTestFixtures.insertEmployee(jdbcTemplate, departmentId, "ADMINVIEW", "770101-02-0001");

        adminToken = jwtService.generateAccessToken(new User(
                adminUserId, LeaveTestFixtures.ADMIN_EMAIL, "hash", UserRole.HR_ADMIN, AccountStatus.ACTIVE, null));
        employeeToken = jwtService.generateAccessToken(
                new User(999_666L, "synthetic-phase7b@test.local", "hash", UserRole.EMPLOYEE, AccountStatus.ACTIVE, 1L));
    }

    @AfterEach
    void tearDown() {
        LeaveTestFixtures.cleanup(jdbcTemplate);
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/employees/{id}/leave-balances", employeeId)).andExpect(status().isUnauthorized());
    }

    @Test
    void employeeCannotUseTheAdminBalanceEndpoint() throws Exception {
        mockMvc.perform(get("/api/employees/{id}/leave-balances", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownEmployeeReturns404() throws Exception {
        mockMvc.perform(get("/api/employees/{id}/leave-balances", employeeId + 999_999)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void hrAdminCanReadAnEmployeesBalancesWithCorrectRemainingDays() throws Exception {
        long leaveTypeId = LeaveTestFixtures.findLeaveTypeIdByName(jdbcTemplate, LeaveTestFixtures.ANNUAL_LEAVE_TYPE_NAME);
        LeaveTestFixtures.insertLeaveBalance(
                jdbcTemplate, employeeId, leaveTypeId, 2026, new BigDecimal("14.0"), new BigDecimal("3.5"));

        mockMvc.perform(get("/api/employees/{id}/leave-balances", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].employeeId").value(employeeId))
                .andExpect(jsonPath("$[0].leaveTypeName").value("Annual Leave"))
                .andExpect(jsonPath("$[0].year").value(2026))
                .andExpect(jsonPath("$[0].entitledDays").value(14.0))
                .andExpect(jsonPath("$[0].usedDays").value(3.5))
                .andExpect(jsonPath("$[0].remainingDays").value(10.5));
    }

    @Test
    void employeeWithNoBalanceRowsReturnsAnEmptyList() throws Exception {
        mockMvc.perform(get("/api/employees/{id}/leave-balances", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void balancesAreOrderedByYearDescendingThenLeaveTypeNameAscendingWithinTheYear() throws Exception {
        long annualLeaveTypeId = LeaveTestFixtures.findLeaveTypeIdByName(jdbcTemplate, LeaveTestFixtures.ANNUAL_LEAVE_TYPE_NAME);
        long sickLeaveTypeId = LeaveTestFixtures.findLeaveTypeIdByName(jdbcTemplate, LeaveTestFixtures.SICK_LEAVE_TYPE_NAME);

        // Inserted deliberately out of order: 2025 Sick, 2026 Sick, 2026 Annual.
        // Expected response order: 2026 Annual, 2026 Sick, 2025 Sick --
        // year descending first, then leave-type name ascending within a year.
        LeaveTestFixtures.insertLeaveBalance(
                jdbcTemplate, employeeId, sickLeaveTypeId, 2025, new BigDecimal("14.0"), new BigDecimal("0.0"));
        LeaveTestFixtures.insertLeaveBalance(
                jdbcTemplate, employeeId, sickLeaveTypeId, 2026, new BigDecimal("14.0"), new BigDecimal("0.0"));
        LeaveTestFixtures.insertLeaveBalance(
                jdbcTemplate, employeeId, annualLeaveTypeId, 2026, new BigDecimal("14.0"), new BigDecimal("0.0"));

        mockMvc.perform(get("/api/employees/{id}/leave-balances", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].year").value(2026))
                .andExpect(jsonPath("$[0].leaveTypeName").value("Annual Leave"))
                .andExpect(jsonPath("$[1].year").value(2026))
                .andExpect(jsonPath("$[1].leaveTypeName").value("Sick Leave"))
                .andExpect(jsonPath("$[2].year").value(2025))
                .andExpect(jsonPath("$[2].leaveTypeName").value("Sick Leave"));
    }
}
