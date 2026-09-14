package com.hrpayroll.backend.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrpayroll.backend.security.jwt.JwtService;
import com.hrpayroll.backend.support.EmployeeManagementTestFixtures;
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
class EmployeeControllerTest {

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
    private long departmentAId;
    private long departmentBId;

    @BeforeEach
    void setUp() {
        EmployeeManagementTestFixtures.cleanup(jdbcTemplate);
        adminUserId = EmployeeManagementTestFixtures.installAdminUser(jdbcTemplate, passwordEncoder);
        departmentAId = EmployeeManagementTestFixtures.installDepartmentA(jdbcTemplate);
        departmentBId = EmployeeManagementTestFixtures.installDepartmentB(jdbcTemplate);

        adminToken = jwtService.generateAccessToken(
                new User(adminUserId, EmployeeManagementTestFixtures.ADMIN_EMAIL, "hash", UserRole.HR_ADMIN, AccountStatus.ACTIVE, null));
        employeeToken = jwtService.generateAccessToken(
                new User(999_777L, "synthetic@test.local", "hash", UserRole.EMPLOYEE, AccountStatus.ACTIVE, 1L));
    }

    @AfterEach
    void tearDown() {
        EmployeeManagementTestFixtures.cleanup(jdbcTemplate);
    }

    private CreateEmployeeRequest createRequest(String codeSuffix, String fullName, long departmentId, String ic) {
        return new CreateEmployeeRequest(
                EmployeeManagementTestFixtures.EMPLOYEE_CODE_PREFIX + codeSuffix,
                fullName,
                ic,
                departmentId,
                "Engineer",
                EmploymentStatus.ACTIVE,
                LocalDate.of(2024, 1, 15),
                "Test Bank",
                "9988776655",
                null);
    }

    private long createEmployee(String codeSuffix, String fullName, long departmentId, String ic) throws Exception {
        String body = mockMvc.perform(post("/api/employees")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest(codeSuffix, fullName, departmentId, ic))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        mockMvc.perform(get("/api/employees")).andExpect(status().isUnauthorized());
    }

    @Test
    void employeeIsForbiddenOnAllAdminEndpoints() throws Exception {
        long id = createEmployee("FORBID", "Forbid Target", departmentAId, "700101-01-0001");

        mockMvc.perform(get("/api/employees").header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/employees/{id}", id).header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/employees")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest("X", "X", departmentAId, "700101-01-9999"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/employees/{id}/status", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateEmployeeStatusRequest(EmploymentStatus.INACTIVE))))
                .andExpect(status().isForbidden());
    }

    @Test
    void hrAdminCanCreateEmployeeAndSensitiveFieldsAreMasked() throws Exception {
        String body = mockMvc.perform(post("/api/employees")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest("CREATE", "Create Target", departmentAId, "700101-01-0002"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.employeeCode").value(EmployeeManagementTestFixtures.EMPLOYEE_CODE_PREFIX + "CREATE"))
                .andExpect(jsonPath("$.departmentId").value(departmentAId))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain("icNumber").doesNotContain("bankName").doesNotContain("bankAccountNo");
        assertThat(body).doesNotContain("700101-01-0002").doesNotContain("9988776655");

        String storedIc = jdbcTemplate.queryForObject(
                "SELECT ic_number FROM employees WHERE employee_code = ?",
                String.class,
                EmployeeManagementTestFixtures.EMPLOYEE_CODE_PREFIX + "CREATE");
        assertThat(storedIc).isEqualTo("700101-01-0002");

        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = 'EMPLOYEE_CREATED' AND actor_id = ?",
                Integer.class,
                adminUserId);
        assertThat(auditCount).isGreaterThanOrEqualTo(1);
    }

    @Test
    void duplicateEmployeeCodeIsRejected() throws Exception {
        createEmployee("DUPCODE", "First", departmentAId, "700101-01-0003");

        mockMvc.perform(post("/api/employees")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest("DUPCODE", "Second", departmentAId, "700101-01-0004"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_EMPLOYEE_CODE"));
    }

    @Test
    void duplicateIcNumberIsRejected() throws Exception {
        createEmployee("ICONE", "First", departmentAId, "700101-01-0005");

        mockMvc.perform(post("/api/employees")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest("ICTWO", "Second", departmentAId, "700101-01-0005"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_IC_NUMBER"));
    }

    @Test
    void invalidDepartmentIsRejectedWith404() throws Exception {
        mockMvc.perform(post("/api/employees")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest("NODEPT", "No Dept", 99_999_999L, "700101-01-0006"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void hrAdminCanViewDetailWithoutSensitiveFields() throws Exception {
        long id = createEmployee("DETAIL", "Detail Target", departmentAId, "700101-01-0007");

        String body = mockMvc.perform(get("/api/employees/{id}", id).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Detail Target"))
                .andExpect(jsonPath("$.departmentName").value(EmployeeManagementTestFixtures.DEPARTMENT_A_NAME))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain("icNumber").doesNotContain("700101-01-0007");
    }

    @Test
    void hrAdminCanUpdateEmployeeAndAuditRecordsChangedFieldNamesOnly() throws Exception {
        long id = createEmployee("UPDATE", "Original Name", departmentAId, "700101-01-0008");

        UpdateEmployeeRequest update = new UpdateEmployeeRequest(
                "Updated Name", "700101-01-0008", departmentBId, "Senior Engineer", EmploymentStatus.ACTIVE,
                LocalDate.of(2024, 1, 15), "Test Bank", "9988776655", null);

        mockMvc.perform(put("/api/employees/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Updated Name"))
                .andExpect(jsonPath("$.departmentId").value(departmentBId))
                .andExpect(jsonPath("$.departmentName").value(EmployeeManagementTestFixtures.DEPARTMENT_B_NAME));

        String details = jdbcTemplate.queryForObject(
                "SELECT details::text FROM audit_logs WHERE action = 'EMPLOYEE_UPDATED' AND entity_id = ? ORDER BY id DESC LIMIT 1",
                String.class,
                id);
        assertThat(details).contains("fullName").contains("departmentId").contains("jobTitle");
        assertThat(details).doesNotContain("Updated Name").doesNotContain("700101-01-0008");
    }

    @Test
    void employeeCodeIsImmutableViaUpdate() throws Exception {
        long id = createEmployee("IMMUTABLE", "Immutable Target", departmentAId, "700101-01-0009");
        String originalCode = EmployeeManagementTestFixtures.EMPLOYEE_CODE_PREFIX + "IMMUTABLE";

        // Raw JSON with an extraneous "employeeCode" field the DTO doesn't declare.
        String rawBody = "{"
                + "\"employeeCode\":\"SHOULD-BE-IGNORED\","
                + "\"fullName\":\"Immutable Target\","
                + "\"icNumber\":\"700101-01-0009\","
                + "\"departmentId\":" + departmentAId + ","
                + "\"jobTitle\":\"Engineer\","
                + "\"employmentStatus\":\"ACTIVE\","
                + "\"hireDate\":\"2024-01-15\""
                + "}";

        mockMvc.perform(put("/api/employees/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rawBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeCode").value(originalCode));
    }

    @Test
    void hrAdminCanChangeStatusAndItIsIdempotent() throws Exception {
        long id = createEmployee("STATUS", "Status Target", departmentAId, "700101-01-0010");

        mockMvc.perform(patch("/api/employees/{id}/status", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateEmployeeStatusRequest(EmploymentStatus.INACTIVE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employmentStatus").value("INACTIVE"));

        // Repeating the same status must be a harmless no-op, not an error,
        // and must not create a second audit row.
        mockMvc.perform(patch("/api/employees/{id}/status", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateEmployeeStatusRequest(EmploymentStatus.INACTIVE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employmentStatus").value("INACTIVE"));

        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = 'EMPLOYEE_STATUS_CHANGED' AND entity_id = ?",
                Integer.class,
                id);
        assertThat(auditCount).isEqualTo(1);

        String details = jdbcTemplate.queryForObject(
                "SELECT details::text FROM audit_logs WHERE action = 'EMPLOYEE_STATUS_CHANGED' AND entity_id = ?",
                String.class,
                id);
        assertThat(details).contains("\"from\": \"ACTIVE\"").contains("\"to\": \"INACTIVE\"");
    }

    @Test
    void listSupportsPaginationDeterministicSortingAndSearch() throws Exception {
        // Every fixture name shares the "Phase5PaginationXyz" token so every
        // assertion below can scope its query with q=Phase5Pagination and get
        // an exact count regardless of whatever else exists in the shared
        // local Postgres instance (e.g. leftover manual-verification rows).
        createEmployee("PAGE1", "Phase5PaginationAlpha Employee", departmentAId, "700101-01-0011");
        createEmployee("PAGE2", "Phase5PaginationBeta Employee", departmentAId, "700101-01-0012");
        createEmployee("PAGE3", "Phase5PaginationGamma Employee", departmentBId, "700101-01-0013");

        // Page 0, size 2, default sort by fullName -> Alpha, Beta first.
        mockMvc.perform(get("/api/employees")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .param("q", "Phase5Pagination")
                        .param("size", "2")
                        .param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].fullName").value("Phase5PaginationAlpha Employee"))
                .andExpect(jsonPath("$.content[1].fullName").value("Phase5PaginationBeta Employee"))
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.page.totalPages").value(2));

        mockMvc.perform(get("/api/employees")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .param("q", "Phase5Pagination")
                        .param("size", "2")
                        .param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].fullName").value("Phase5PaginationGamma Employee"));

        // Search by a more specific partial name.
        mockMvc.perform(get("/api/employees")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .param("q", "Phase5PaginationBeta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].fullName").value("Phase5PaginationBeta Employee"));

        // Filter by department (scoped with q so unrelated department-B rows can't inflate the count).
        mockMvc.perform(get("/api/employees")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .param("q", "Phase5Pagination")
                        .param("departmentId", String.valueOf(departmentBId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].fullName").value("Phase5PaginationGamma Employee"));
    }

    @Test
    void listNeverExposesSensitiveFields() throws Exception {
        createEmployee("SENSITIVE", "Sensitive Target", departmentAId, "700101-01-0014");

        String body = mockMvc.perform(get("/api/employees").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain("icNumber").doesNotContain("bankAccountNo").doesNotContain("700101-01-0014");
    }
}
