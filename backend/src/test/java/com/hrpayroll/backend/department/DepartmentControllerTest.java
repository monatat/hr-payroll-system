package com.hrpayroll.backend.department;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class DepartmentControllerTest {

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

    private long adminUserId;
    private String adminToken;
    private String employeeToken;

    @BeforeEach
    void setUp() {
        EmployeeManagementTestFixtures.cleanup(jdbcTemplate);
        adminUserId = EmployeeManagementTestFixtures.installAdminUser(jdbcTemplate, passwordEncoder);
        adminToken = jwtService.generateAccessToken(
                new User(adminUserId, EmployeeManagementTestFixtures.ADMIN_EMAIL, "hash", UserRole.HR_ADMIN, AccountStatus.ACTIVE, null));
        employeeToken = jwtService.generateAccessToken(
                new User(999_888L, "synthetic@test.local", "hash", UserRole.EMPLOYEE, AccountStatus.ACTIVE, 1L));
    }

    @AfterEach
    void tearDown() {
        EmployeeManagementTestFixtures.cleanup(jdbcTemplate);
    }

    @Test
    void unauthenticatedListIsRejected() throws Exception {
        mockMvc.perform(get("/api/departments")).andExpect(status().isUnauthorized());
    }

    @Test
    void anyAuthenticatedUserCanListDepartments() throws Exception {
        mockMvc.perform(get("/api/departments").header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken))
                .andExpect(status().isOk());
    }

    @Test
    void hrAdminCanCreateDepartment() throws Exception {
        mockMvc.perform(post("/api/departments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateDepartmentRequest(EmployeeManagementTestFixtures.DEPARTMENT_A_NAME))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(EmployeeManagementTestFixtures.DEPARTMENT_A_NAME));

        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = 'DEPARTMENT_CREATED' AND actor_id = ? "
                        + "AND details->>'name' = ?",
                Integer.class,
                adminUserId,
                EmployeeManagementTestFixtures.DEPARTMENT_A_NAME);
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    void employeeCannotCreateDepartment() throws Exception {
        mockMvc.perform(post("/api/departments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateDepartmentRequest("Should Not Be Created"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void blankNameIsRejected() throws Exception {
        mockMvc.perform(post("/api/departments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateDepartmentRequest("  "))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void duplicateNameIsRejectedWithConflict() throws Exception {
        mockMvc.perform(post("/api/departments")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateDepartmentRequest(EmployeeManagementTestFixtures.DEPARTMENT_A_NAME))));

        mockMvc.perform(post("/api/departments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateDepartmentRequest(EmployeeManagementTestFixtures.DEPARTMENT_A_NAME))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_DEPARTMENT_NAME"));
    }

    @Test
    void listIsDeterministicallyOrderedByName() throws Exception {
        // Insert out of alphabetical order.
        mockMvc.perform(post("/api/departments")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateDepartmentRequest(EmployeeManagementTestFixtures.DEPARTMENT_B_NAME))));
        mockMvc.perform(post("/api/departments")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateDepartmentRequest(EmployeeManagementTestFixtures.DEPARTMENT_A_NAME))));

        String body = mockMvc.perform(get("/api/departments").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        int indexOfA = body.indexOf(EmployeeManagementTestFixtures.DEPARTMENT_A_NAME);
        int indexOfB = body.indexOf(EmployeeManagementTestFixtures.DEPARTMENT_B_NAME);
        assertThat(indexOfA).isGreaterThanOrEqualTo(0);
        assertThat(indexOfB).isGreaterThanOrEqualTo(0);
        assertThat(indexOfA).isLessThan(indexOfB);
    }
}
