package com.hrpayroll.backend.salary;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hrpayroll.backend.security.jwt.JwtService;
import com.hrpayroll.backend.support.SalaryStructureTestFixtures;
import com.hrpayroll.backend.user.AccountStatus;
import com.hrpayroll.backend.user.User;
import com.hrpayroll.backend.user.UserRole;
import java.time.LocalDate;
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
class SalaryStructureSelfServiceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    private long departmentId;
    private long employeeId;
    private long otherEmployeeId;
    private long adminUserId;
    private String adminToken;
    private String employeeToken;

    @BeforeEach
    void setUp() {
        SalaryStructureTestFixtures.cleanup(jdbcTemplate);
        departmentId = SalaryStructureTestFixtures.installDepartment(jdbcTemplate);
        adminUserId = SalaryStructureTestFixtures.installAdminUser(jdbcTemplate, passwordEncoder);

        long[] employeeAndUser = SalaryStructureTestFixtures.installEmployeeWithUser(
                jdbcTemplate, passwordEncoder, departmentId, "SELF", "770101-01-0002");
        employeeId = employeeAndUser[0];
        long employeeUserId = employeeAndUser[1];

        long[] otherEmployeeAndUser = SalaryStructureTestFixtures.installOtherEmployeeWithUser(
                jdbcTemplate, passwordEncoder, departmentId, "OTHER", "770101-01-0003");
        otherEmployeeId = otherEmployeeAndUser[0];

        employeeToken = jwtService.generateAccessToken(new User(
                employeeUserId, SalaryStructureTestFixtures.EMPLOYEE_EMAIL, "hash", UserRole.EMPLOYEE,
                AccountStatus.ACTIVE, employeeId));
        adminToken = jwtService.generateAccessToken(new User(
                adminUserId, SalaryStructureTestFixtures.ADMIN_EMAIL, "hash", UserRole.HR_ADMIN, AccountStatus.ACTIVE, null));
    }

    @AfterEach
    void tearDown() {
        SalaryStructureTestFixtures.cleanup(jdbcTemplate);
    }

    private void createStructure(long forEmployeeId, String basePay, LocalDate from) throws Exception {
        mockMvc.perform(post("/api/employees/{id}/salary-structures", forEmployeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"basePay\":" + basePay + ",\"effectiveFrom\":\"" + from + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/employees/me/salary-structures")).andExpect(status().isUnauthorized());
    }

    @Test
    void hrAdminIsForbiddenOnSelfServiceEndpoint() throws Exception {
        mockMvc.perform(get("/api/employees/me/salary-structures").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void employeeCanReadOwnSalaryHistory() throws Exception {
        createStructure(employeeId, "5000.00", LocalDate.of(2026, 1, 1));

        mockMvc.perform(get("/api/employees/me/salary-structures").header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].employeeId").value(employeeId))
                .andExpect(jsonPath("$[0].basePay").value(5000.00));
    }

    @Test
    void employeeNeverSeesAnotherEmployeesSalaryHistory() throws Exception {
        createStructure(employeeId, "5000.00", LocalDate.of(2026, 1, 1));
        createStructure(otherEmployeeId, "9000.00", LocalDate.of(2026, 1, 1));

        String body = mockMvc.perform(
                        get("/api/employees/me/salary-structures").header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("9000.00").contains("5000.00");
    }

    @Test
    void selfServiceEndpointHasNoIdParameterToSpoof() throws Exception {
        // There is no /api/employees/{id}/salary-structures/me-style route --
        // the self-service path itself carries no client-suppliable id.
        mockMvc.perform(get("/api/employees/{id}/salary-structures", otherEmployeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken))
                .andExpect(status().isForbidden());
    }
}
