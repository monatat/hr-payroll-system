package com.hrpayroll.backend.leave;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hrpayroll.backend.security.jwt.JwtService;
import com.hrpayroll.backend.support.LeaveTestFixtures;
import com.hrpayroll.backend.user.AccountStatus;
import com.hrpayroll.backend.user.User;
import com.hrpayroll.backend.user.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class LeaveTypeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtService jwtService;

    private String employeeToken;

    @BeforeEach
    void setUp() {
        LeaveTestFixtures.cleanup(jdbcTemplate);
        employeeToken = jwtService.generateAccessToken(
                new User(999_777L, "synthetic-phase7@test.local", "hash", UserRole.EMPLOYEE, AccountStatus.ACTIVE, 1L));
    }

    @AfterEach
    void tearDown() {
        LeaveTestFixtures.cleanup(jdbcTemplate);
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/leave-types")).andExpect(status().isUnauthorized());
    }

    @Test
    void anyAuthenticatedRoleCanListLeaveTypesAlphabetically() throws Exception {
        mockMvc.perform(get("/api/leave-types").header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Annual Leave"))
                .andExpect(jsonPath("$[0].defaultAnnualEntitlementDays").value(14.0))
                .andExpect(jsonPath("$[1].name").value("Sick Leave"))
                .andExpect(jsonPath("$[1].defaultAnnualEntitlementDays").value(14.0));
    }
}
