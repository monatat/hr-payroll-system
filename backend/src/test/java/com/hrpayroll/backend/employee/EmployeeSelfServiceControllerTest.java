package com.hrpayroll.backend.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrpayroll.backend.security.jwt.JwtService;
import com.hrpayroll.backend.support.EmployeeManagementTestFixtures;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class EmployeeSelfServiceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ObjectMapper objectMapper;

    private long departmentAId;
    private long employeeId;
    private long employeeUserId;
    private long adminUserId;
    private String employeeToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        EmployeeManagementTestFixtures.cleanup(jdbcTemplate);
        departmentAId = EmployeeManagementTestFixtures.installDepartmentA(jdbcTemplate);
        adminUserId = EmployeeManagementTestFixtures.installAdminUser(jdbcTemplate, passwordEncoder);

        long[] employeeAndUser = EmployeeManagementTestFixtures.installEmployeeWithUser(
                jdbcTemplate, passwordEncoder, departmentAId, "ME", "710101-01-0001");
        employeeId = employeeAndUser[0];
        employeeUserId = employeeAndUser[1];

        employeeToken = jwtService.generateAccessToken(
                new User(employeeUserId, EmployeeManagementTestFixtures.EMPLOYEE_EMAIL, "hash", UserRole.EMPLOYEE,
                        AccountStatus.ACTIVE, employeeId));
        adminToken = jwtService.generateAccessToken(
                new User(adminUserId, EmployeeManagementTestFixtures.ADMIN_EMAIL, "hash", UserRole.HR_ADMIN, AccountStatus.ACTIVE, null));
    }

    @AfterEach
    void tearDown() {
        EmployeeManagementTestFixtures.cleanup(jdbcTemplate);
    }

    @Test
    void unauthenticatedMeIsRejected() throws Exception {
        mockMvc.perform(get("/api/employees/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void hrAdminIsForbiddenOnMeEndpoints() throws Exception {
        mockMvc.perform(get("/api/employees/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/employees/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateOwnProfileRequest("0123456789"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void employeeCanReadOwnProfileWithoutSensitiveFields() throws Exception {
        String body = mockMvc.perform(get("/api/employees/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(employeeId))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain("icNumber").doesNotContain("710101-01-0001").doesNotContain("bankAccountNo");
    }

    @Test
    void employeeCannotAccessTheAdminDetailRouteForOwnOrAnyId() throws Exception {
        // The self-service endpoint has no {id} at all -- /me is the only
        // route an EMPLOYEE can use. The HR_ADMIN-only {id} route rejects
        // an EMPLOYEE token regardless of whose id is requested.
        mockMvc.perform(get("/api/employees/{id}", employeeId).header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/employees/{id}", employeeId + 999).header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void employeeCanUpdateOwnPhoneNumberAndAuditRecordsFieldNameOnly() throws Exception {
        mockMvc.perform(put("/api/employees/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateOwnProfileRequest("0123456789"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneNumber").value("0123456789"));

        String storedPhone = jdbcTemplate.queryForObject(
                "SELECT phone_number FROM employees WHERE id = ?", String.class, employeeId);
        assertThat(storedPhone).isEqualTo("0123456789");

        String details = jdbcTemplate.queryForObject(
                "SELECT details::text FROM audit_logs WHERE action = 'EMPLOYEE_SELF_PROFILE_UPDATED' AND actor_id = ?",
                String.class,
                employeeUserId);
        assertThat(details).contains("phoneNumber");
        assertThat(details).doesNotContain("0123456789");
    }

    @Test
    void employeeCannotModifyProtectedFieldsViaSelfService() throws Exception {
        // Raw JSON with fields the narrow DTO doesn't declare -- must be
        // silently ignored, never applied.
        String rawBody = "{"
                + "\"phoneNumber\":\"0198765432\","
                + "\"fullName\":\"Hacked Name\","
                + "\"employmentStatus\":\"INACTIVE\","
                + "\"departmentId\":999999,"
                + "\"icNumber\":\"999999-99-9999\""
                + "}";

        mockMvc.perform(put("/api/employees/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rawBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneNumber").value("0198765432"));

        String fullName = jdbcTemplate.queryForObject("SELECT full_name FROM employees WHERE id = ?", String.class, employeeId);
        String employmentStatus =
                jdbcTemplate.queryForObject("SELECT employment_status FROM employees WHERE id = ?", String.class, employeeId);
        Long storedDepartmentId =
                jdbcTemplate.queryForObject("SELECT department_id FROM employees WHERE id = ?", Long.class, employeeId);

        assertThat(fullName).isNotEqualTo("Hacked Name");
        assertThat(employmentStatus).isEqualTo("ACTIVE");
        assertThat(storedDepartmentId).isEqualTo(departmentAId);
    }
}
