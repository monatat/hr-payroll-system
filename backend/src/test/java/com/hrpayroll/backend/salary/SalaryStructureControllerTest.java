package com.hrpayroll.backend.salary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrpayroll.backend.security.jwt.JwtService;
import com.hrpayroll.backend.support.SalaryStructureTestFixtures;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class SalaryStructureControllerTest {

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

    private long departmentId;
    private long employeeId;
    private long adminUserId;
    private String adminToken;
    private String employeeToken;

    @BeforeEach
    void setUp() {
        SalaryStructureTestFixtures.cleanup(jdbcTemplate);
        departmentId = SalaryStructureTestFixtures.installDepartment(jdbcTemplate);
        adminUserId = SalaryStructureTestFixtures.installAdminUser(jdbcTemplate, passwordEncoder);
        employeeId = SalaryStructureTestFixtures.insertEmployee(jdbcTemplate, departmentId, "A", "770101-01-0001");

        adminToken = jwtService.generateAccessToken(new User(
                adminUserId, SalaryStructureTestFixtures.ADMIN_EMAIL, "hash", UserRole.HR_ADMIN, AccountStatus.ACTIVE, null));
        employeeToken = jwtService.generateAccessToken(
                new User(999_888L, "synthetic-phase6@test.local", "hash", UserRole.EMPLOYEE, AccountStatus.ACTIVE, 1L));
    }

    @AfterEach
    void tearDown() {
        SalaryStructureTestFixtures.cleanup(jdbcTemplate);
    }

    private String createBody(String basePay, String housing, String transport, LocalDate from, LocalDate to)
            throws Exception {
        StringBuilder json = new StringBuilder("{");
        json.append("\"basePay\":").append(basePay).append(",");
        if (housing != null) {
            json.append("\"housingAllowance\":").append(housing).append(",");
        }
        if (transport != null) {
            json.append("\"transportAllowance\":").append(transport).append(",");
        }
        json.append("\"effectiveFrom\":\"").append(from).append("\"");
        if (to != null) {
            json.append(",\"effectiveTo\":\"").append(to).append("\"");
        }
        json.append("}");
        return json.toString();
    }

    // ---- Authorization ----

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        mockMvc.perform(get("/api/employees/{id}/salary-structures", employeeId)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/employees/{id}/salary-structures", employeeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("5000.00", null, null, LocalDate.of(2026, 1, 1), null)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/salary-structures/{id}", 1)).andExpect(status().isUnauthorized());
    }

    @Test
    void employeeCannotAccessHrAdminSalaryEndpoints() throws Exception {
        mockMvc.perform(get("/api/employees/{id}/salary-structures", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/employees/{id}/salary-structures", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("5000.00", null, null, LocalDate.of(2026, 1, 1), null)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/salary-structures/{id}", 1).header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken))
                .andExpect(status().isForbidden());
    }

    // ---- Create / read / list ----

    @Test
    void hrAdminCanCreateAndReadSalaryStructure() throws Exception {
        String body = mockMvc.perform(post("/api/employees/{id}/salary-structures", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("5000.00", "500.00", "200.00", LocalDate.of(2026, 1, 1), null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.employeeId").value(employeeId))
                .andExpect(jsonPath("$.basePay").value(5000.00))
                .andExpect(jsonPath("$.housingAllowance").value(500.00))
                .andExpect(jsonPath("$.transportAllowance").value(200.00))
                .andExpect(jsonPath("$.effectiveFrom").value("2026-01-01"))
                .andExpect(jsonPath("$.effectiveTo").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();

        long createdId = objectMapper.readTree(body).get("id").asLong();

        mockMvc.perform(get("/api/salary-structures/{id}", createdId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(createdId))
                .andExpect(jsonPath("$.employeeId").value(employeeId));
    }

    @Test
    void hrAdminCanListEmployeeSalaryHistory() throws Exception {
        mockMvc.perform(post("/api/employees/{id}/salary-structures", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("4000.00", null, null, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/employees/{id}/salary-structures", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("4500.00", null, null, LocalDate.of(2026, 7, 1), null)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/employees/{id}/salary-structures", employeeId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].effectiveFrom").value("2026-01-01"))
                .andExpect(jsonPath("$[1].effectiveFrom").value("2026-07-01"));
    }

    // ---- Allowance normalization ----

    @Test
    void omittedAllowancesNormalizeToZero() throws Exception {
        mockMvc.perform(post("/api/employees/{id}/salary-structures", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("3000.00", null, null, LocalDate.of(2026, 1, 1), null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.housingAllowance").value(0))
                .andExpect(jsonPath("$.transportAllowance").value(0));
    }

    // ---- 404s ----

    @Test
    void createForUnknownEmployeeReturns404() throws Exception {
        mockMvc.perform(post("/api/employees/{id}/salary-structures", employeeId + 999_999)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("5000.00", null, null, LocalDate.of(2026, 1, 1), null)))
                .andExpect(status().isNotFound());
    }

    @Test
    void listForUnknownEmployeeReturns404() throws Exception {
        mockMvc.perform(get("/api/employees/{id}/salary-structures", employeeId + 999_999)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void getUnknownSalaryStructureReturns404() throws Exception {
        mockMvc.perform(get("/api/salary-structures/{id}", 999_999_999L).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    // ---- Validation ----

    @Test
    void negativeBasePayReturns400() throws Exception {
        mockMvc.perform(post("/api/employees/{id}/salary-structures", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("-1.00", null, null, LocalDate.of(2026, 1, 1), null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void negativeHousingAllowanceReturns400() throws Exception {
        mockMvc.perform(post("/api/employees/{id}/salary-structures", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("5000.00", "-100.00", null, LocalDate.of(2026, 1, 1), null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void negativeTransportAllowanceReturns400() throws Exception {
        mockMvc.perform(post("/api/employees/{id}/salary-structures", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("5000.00", null, "-1.00", LocalDate.of(2026, 1, 1), null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void excessivePrecisionReturns400() throws Exception {
        mockMvc.perform(post("/api/employees/{id}/salary-structures", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("5000.123", null, null, LocalDate.of(2026, 1, 1), null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingEffectiveFromReturns400() throws Exception {
        String rawBody = "{\"basePay\":5000.00}";
        mockMvc.perform(post("/api/employees/{id}/salary-structures", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rawBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingBasePayReturns400() throws Exception {
        String rawBody = "{\"effectiveFrom\":\"2026-01-01\"}";
        mockMvc.perform(post("/api/employees/{id}/salary-structures", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rawBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    void effectiveToBeforeEffectiveFromReturns400() throws Exception {
        mockMvc.perform(post("/api/employees/{id}/salary-structures", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(
                                "5000.00", null, null, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 1, 1))))
                .andExpect(status().isBadRequest());
    }

    // ---- Open-ended / closed / future-dated / gaps ----

    @Test
    void openEndedSalaryStructureSucceeds() throws Exception {
        mockMvc.perform(post("/api/employees/{id}/salary-structures", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("5000.00", null, null, LocalDate.of(2026, 1, 1), null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.effectiveTo").doesNotExist());
    }

    @Test
    void closedSalaryStructureSucceeds() throws Exception {
        mockMvc.perform(post("/api/employees/{id}/salary-structures", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(
                                "5000.00", null, null, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.effectiveTo").value("2026-06-30"));
    }

    @Test
    void futureDatedSalaryStructureSucceeds() throws Exception {
        mockMvc.perform(post("/api/employees/{id}/salary-structures", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("6000.00", null, null, LocalDate.of(2030, 1, 1), null)))
                .andExpect(status().isCreated());
    }

    @Test
    void gapsBetweenStructuresAreAllowed() throws Exception {
        mockMvc.perform(post("/api/employees/{id}/salary-structures", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(
                                "4000.00", null, null, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31))))
                .andExpect(status().isCreated());
        // Deliberate gap: 2026-02-01 .. 2026-02-28 has no structure at all.
        mockMvc.perform(post("/api/employees/{id}/salary-structures", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("4200.00", null, null, LocalDate.of(2026, 3, 1), null)))
                .andExpect(status().isCreated());
    }

    // ---- Overlap / succession edge cases (from approved spec) ----

    @Test
    void adjacentRangesSucceed() throws Exception {
        mockMvc.perform(post("/api/employees/{id}/salary-structures", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(
                                "4000.00", null, null, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/employees/{id}/salary-structures", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("4500.00", null, null, LocalDate.of(2026, 7, 1), null)))
                .andExpect(status().isCreated());
    }

    @Test
    void sameDayBoundaryOverlapReturns409WithCode() throws Exception {
        mockMvc.perform(post("/api/employees/{id}/salary-structures", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(
                                "4000.00", null, null, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/employees/{id}/salary-structures", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("4500.00", null, null, LocalDate.of(2026, 6, 30), null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SALARY_STRUCTURE_OVERLAP"));
    }

    @Test
    void sameEffectiveFromReturns409() throws Exception {
        mockMvc.perform(post("/api/employees/{id}/salary-structures", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(
                                "4000.00", null, null, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/employees/{id}/salary-structures", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(
                                "4500.00", null, null, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SALARY_STRUCTURE_OVERLAP"));
    }

    @Test
    void edgeCaseA_openEndedSupersededByLaterOpenEndedSucceedsAndClosesPrevious() throws Exception {
        String firstBody = mockMvc.perform(post("/api/employees/{id}/salary-structures", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("4000.00", null, null, LocalDate.of(2026, 1, 1), null)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long firstId = objectMapper.readTree(firstBody).get("id").asLong();

        mockMvc.perform(post("/api/employees/{id}/salary-structures", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("4500.00", null, null, LocalDate.of(2026, 7, 1), null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.effectiveTo").doesNotExist());

        mockMvc.perform(get("/api/salary-structures/{id}", firstId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectiveTo").value("2026-06-30"))
                .andExpect(jsonPath("$.basePay").value(4000.00)); // untouched except effectiveTo
    }

    @Test
    void edgeCaseB_backdatedOpenEndedIsRejectedAndDoesNotMutateExisting() throws Exception {
        String firstBody = mockMvc.perform(post("/api/employees/{id}/salary-structures", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("4000.00", null, null, LocalDate.of(2026, 1, 1), null)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long firstId = objectMapper.readTree(firstBody).get("id").asLong();

        mockMvc.perform(post("/api/employees/{id}/salary-structures", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("4500.00", null, null, LocalDate.of(2025, 12, 1), null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SALARY_STRUCTURE_OVERLAP"));

        // Must remain untouched: still open-ended, original value.
        mockMvc.perform(get("/api/salary-structures/{id}", firstId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectiveTo").doesNotExist())
                .andExpect(jsonPath("$.basePay").value(4000.00));
    }

    @Test
    void edgeCaseC_closedThenOpenEndedSucceeds() throws Exception {
        mockMvc.perform(post("/api/employees/{id}/salary-structures", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(
                                "4000.00", null, null, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/employees/{id}/salary-structures", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("4500.00", null, null, LocalDate.of(2026, 7, 1), null)))
                .andExpect(status().isCreated());
    }

    @Test
    void edgeCaseD_closedThenOverlappingBoundaryIsRejected() throws Exception {
        mockMvc.perform(post("/api/employees/{id}/salary-structures", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(
                                "4000.00", null, null, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/employees/{id}/salary-structures", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("4500.00", null, null, LocalDate.of(2026, 6, 30), null)))
                .andExpect(status().isConflict());
    }

    @Test
    void edgeCaseE_closedThenClosedNonOverlappingSucceeds() throws Exception {
        mockMvc.perform(post("/api/employees/{id}/salary-structures", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(
                                "4000.00", null, null, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/employees/{id}/salary-structures", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(
                                "4500.00", null, null, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31))))
                .andExpect(status().isCreated());
    }

    @Test
    void edgeCaseF_newRangeOverlappingALaterStructureIsRejectedDespiteNoSuccession() throws Exception {
        mockMvc.perform(post("/api/employees/{id}/salary-structures", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(
                                "4000.00", null, null, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/employees/{id}/salary-structures", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("4800.00", null, null, LocalDate.of(2026, 8, 1), null)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/employees/{id}/salary-structures", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(
                                "4500.00", null, null, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 15))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SALARY_STRUCTURE_OVERLAP"));
    }

    // ---- Audit ----

    @Test
    void createWritesAuditEventWithoutRawAmounts() throws Exception {
        mockMvc.perform(post("/api/employees/{id}/salary-structures", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("9999.99", "111.11", "222.22", LocalDate.of(2026, 1, 1), null)))
                .andExpect(status().isCreated());

        String details = jdbcTemplate.queryForObject(
                "SELECT details::text FROM audit_logs WHERE action = 'SALARY_STRUCTURE_CREATED' AND actor_id = ?",
                String.class,
                adminUserId);
        assertThat(details).contains("employeeId").contains("effectiveFrom");
        assertThat(details).doesNotContain("9999.99").doesNotContain("111.11").doesNotContain("222.22");
    }

    @Test
    void successionAuditRecordsClosedPreviousStructureIdWithoutAmounts() throws Exception {
        String firstBody = mockMvc.perform(post("/api/employees/{id}/salary-structures", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("4000.00", null, null, LocalDate.of(2026, 1, 1), null)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long firstId = objectMapper.readTree(firstBody).get("id").asLong();

        mockMvc.perform(post("/api/employees/{id}/salary-structures", employeeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("4500.00", null, null, LocalDate.of(2026, 7, 1), null)))
                .andExpect(status().isCreated());

        String details = jdbcTemplate.queryForObject(
                "SELECT details::text FROM audit_logs WHERE action = 'SALARY_STRUCTURE_CREATED' AND actor_id = ? "
                        + "AND details->>'closedPreviousStructureId' IS NOT NULL",
                String.class,
                adminUserId);
        assertThat(details).contains(String.valueOf(firstId));
        assertThat(details).doesNotContain("4000.00").doesNotContain("4500.00");
    }
}
