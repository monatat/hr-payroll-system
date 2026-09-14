package com.hrpayroll.backend.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hrpayroll.backend.security.jwt.JwtService;
import com.hrpayroll.backend.support.SecurityTestFixtures;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class EmployeeSensitiveControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    private long employeeId;
    private long adminUserId;

    @BeforeEach
    void seedFixtures() {
        employeeId = SecurityTestFixtures.install(jdbcTemplate, passwordEncoder);
        adminUserId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, SecurityTestFixtures.ADMIN_EMAIL);
    }

    @AfterEach
    void cleanupFixtures() {
        SecurityTestFixtures.cleanup(jdbcTemplate);
    }

    @Test
    void missingTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/employees/{id}/sensitive", employeeId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void invalidTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/employees/{id}/sensitive", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void employeeRoleIsDenied() throws Exception {
        User employeeUser = new User(999L, "synthetic-employee@test.local", "hash", UserRole.EMPLOYEE,
                AccountStatus.ACTIVE, employeeId);
        String token = jwtService.generateAccessToken(employeeUser);

        mockMvc.perform(get("/api/employees/{id}/sensitive", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void hrAdminCanReadUnmaskedSensitiveDataAndAuditIsWritten() throws Exception {
        User adminUser = new User(adminUserId, SecurityTestFixtures.ADMIN_EMAIL, "hash", UserRole.HR_ADMIN,
                AccountStatus.ACTIVE, null);
        String token = jwtService.generateAccessToken(adminUser);

        mockMvc.perform(get("/api/employees/{id}/sensitive", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.icNumber").value(SecurityTestFixtures.EMPLOYEE_IC_NUMBER))
                .andExpect(jsonPath("$.bankName").value(SecurityTestFixtures.EMPLOYEE_BANK_NAME))
                .andExpect(jsonPath("$.bankAccountNo").value(SecurityTestFixtures.EMPLOYEE_BANK_ACCOUNT_NO));

        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = 'SENSITIVE_DATA_ACCESS' "
                        + "AND entity_name = 'EMPLOYEE' AND entity_id = ? AND actor_id = ?",
                Integer.class,
                employeeId,
                adminUserId);
        assertThat(auditCount).isEqualTo(1);

        String details = jdbcTemplate.queryForObject(
                "SELECT details::text FROM audit_logs WHERE action = 'SENSITIVE_DATA_ACCESS' AND entity_id = ?",
                String.class,
                employeeId);
        assertThat(details).doesNotContain(SecurityTestFixtures.EMPLOYEE_IC_NUMBER);
        assertThat(details).doesNotContain(SecurityTestFixtures.EMPLOYEE_BANK_ACCOUNT_NO);
    }
}
