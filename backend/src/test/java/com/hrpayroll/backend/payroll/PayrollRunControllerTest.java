package com.hrpayroll.backend.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrpayroll.backend.security.jwt.JwtService;
import com.hrpayroll.backend.support.PayrollTestFixtures;
import com.hrpayroll.backend.user.AccountStatus;
import com.hrpayroll.backend.user.User;
import com.hrpayroll.backend.user.UserRole;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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

/**
 * Real-PostgreSQL controller/integration tests for the HR_ADMIN payroll-run
 * endpoints (Phase 8B-2A): create-and-calculate, list, detail, and a run's
 * payslip list. Uses CONTROLLER_TEST_PERIOD_YEAR (2006) exclusively, plus
 * 2007 for the year-filter test -- verified safe the same way as every
 * other Phase 8B period: no existing employee (P7DEMO01/EMP001, both hired
 * 2025/2026) can ever qualify as a candidate for either year.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PayrollRunControllerTest {

    private static final int YEAR = PayrollTestFixtures.CONTROLLER_TEST_PERIOD_YEAR;
    private static final int OTHER_YEAR = YEAR + 1;
    private static final LocalDate BEFORE_PERIOD = LocalDate.of(1999, 1, 1);

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
    private long adminUserId;
    private String adminToken;
    private String employeeToken;
    private final List<Long> extraRunIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        extraRunIds.clear();
        PayrollTestFixtures.cleanup(jdbcTemplate);
        departmentId = PayrollTestFixtures.installDepartment(jdbcTemplate);
        adminUserId = PayrollTestFixtures.installAdminUser(jdbcTemplate, passwordEncoder);
        // Hired "today" (the 3-arg overload's CURRENT_DATE), deliberately -- this employee exists
        // only to hold the EMPLOYEE JWT used by the 401/403 tests, and every test period here is
        // years in the past, so it must never itself qualify as a payroll candidate (hireDate <=
        // periodStart would be false) and pollute a creation test's candidate set.
        long employeeUserId =
                PayrollTestFixtures.insertEmployee(jdbcTemplate, departmentId, "TOKEN-EMP", "000601010099");
        long tokenEmployeeUserRowId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, role, account_status, employee_id) "
                        + "VALUES ('phase8b2a.employee@test.local', ?, 'EMPLOYEE', 'ACTIVE', ?) RETURNING id",
                Long.class,
                passwordEncoder.encode("Phase8B2AEmployeePass123!"),
                employeeUserId);

        adminToken = jwtService.generateAccessToken(
                new User(adminUserId, PayrollTestFixtures.ADMIN_EMAIL, "hash", UserRole.HR_ADMIN, AccountStatus.ACTIVE, null));
        employeeToken = jwtService.generateAccessToken(new User(
                tokenEmployeeUserRowId,
                "phase8b2a.employee@test.local",
                "hash",
                UserRole.EMPLOYEE,
                AccountStatus.ACTIVE,
                employeeUserId));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM users WHERE email = ?", "phase8b2a.employee@test.local");
        PayrollTestFixtures.cleanup(jdbcTemplate, extraRunIds);
    }

    private long insertCandidateWithCoverage(String codeSuffix, int month, BigDecimal basePay) {
        long employeeId = PayrollTestFixtures.insertEmployee(
                jdbcTemplate, departmentId, codeSuffix, "0006" + codeSuffix.hashCode() % 100000, BEFORE_PERIOD, "ACTIVE");
        LocalDate periodStart = LocalDate.of(YEAR, month, 1);
        PayrollTestFixtures.insertSalaryStructure(
                jdbcTemplate, employeeId, basePay, BigDecimal.ZERO, BigDecimal.ZERO, periodStart, null);
        return employeeId;
    }

    private String createBody(int month, int year) throws Exception {
        return objectMapper.writeValueAsString(new CreatePayrollRunRequest(month, year));
    }

    // ---- Creation ----

    @Test
    void hrAdminValidRequestReturns201WithCalculatedRunAndPersistedPayslip() throws Exception {
        int month = 1;
        insertCandidateWithCoverage("C1", month, new BigDecimal("5000.00"));

        mockMvc.perform(post("/api/payroll-runs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(month, YEAR)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CALCULATED"))
                .andExpect(jsonPath("$.periodMonth").value(month))
                .andExpect(jsonPath("$.periodYear").value(YEAR))
                .andExpect(jsonPath("$.periodStart").value(LocalDate.of(YEAR, month, 1).toString()))
                .andExpect(jsonPath("$.periodEnd").value(LocalDate.of(YEAR, month, 31).toString()))
                .andExpect(jsonPath("$.failureReason").doesNotExist());

        Long runId = jdbcTemplate.queryForObject(
                "SELECT id FROM payroll_runs WHERE period_month = ? AND period_year = ?", Long.class, month, YEAR);
        Integer payslipCount =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payslips WHERE payroll_run_id = ?", Integer.class, runId);
        assertThat(payslipCount).isEqualTo(1);
    }

    @Test
    void requestCannotOverrideRatesStatusPeriodDatesPayOrPayslips() throws Exception {
        int month = 1;
        insertCandidateWithCoverage("C1", month, new BigDecimal("5000.00"));

        // A client sending extra fields that don't exist on CreatePayrollRunRequest must have them
        // silently ignored -- there is no way to spoof status/period dates/rates/actor this way.
        String maliciousBody = "{\"month\":" + month + ",\"year\":" + YEAR
                + ",\"status\":\"COMPLETED\",\"periodStart\":\"2000-01-01\",\"epfRateSnapshot\":\"0.99\","
                + "\"actorUserId\":999999,\"basePay\":\"999999.99\"}";

        mockMvc.perform(post("/api/payroll-runs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maliciousBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CALCULATED"))
                .andExpect(jsonPath("$.epfRateSnapshot").value(0.1100));
    }

    @Test
    void invalidMonthBelowRangeReturns400() throws Exception {
        mockMvc.perform(post("/api/payroll-runs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(0, YEAR)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidMonthAboveRangeReturns400() throws Exception {
        mockMvc.perform(post("/api/payroll-runs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(13, YEAR)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidYearBelowRangeReturns400() throws Exception {
        mockMvc.perform(post("/api/payroll-runs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(1, 1999)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidYearAboveRangeReturns400() throws Exception {
        mockMvc.perform(post("/api/payroll-runs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(1, 2101)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nullFieldsReturn400() throws Exception {
        mockMvc.perform(post("/api/payroll-runs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void duplicatePeriodReturns409WithStableCode() throws Exception {
        int month = 2;
        insertCandidateWithCoverage("C2", month, new BigDecimal("1000.00"));
        mockMvc.perform(post("/api/payroll-runs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(month, YEAR)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/payroll-runs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(month, YEAR)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYROLL_RUN_PERIOD_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.detail", org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Exception"))));
    }

    @Test
    void calculationFailureReturns422WithCodeRunIdAndFailureCodeAndCommitsFailedRunWithZeroPayslips() throws Exception {
        int month = 3; // no candidate ever inserted for this month -- NO_ELIGIBLE_EMPLOYEES

        String responseBody = mockMvc.perform(post("/api/payroll-runs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(month, YEAR)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PAYROLL_RUN_FAILED"))
                .andExpect(jsonPath("$.failureCode").value("NO_ELIGIBLE_EMPLOYEES"))
                .andExpect(jsonPath("$.payrollRunId").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(responseBody).doesNotContain("Exception").doesNotContain("SELECT").doesNotContain("at com.");

        Long runId = objectMapper.readTree(responseBody).get("payrollRunId").asLong();
        String status = jdbcTemplate.queryForObject("SELECT status FROM payroll_runs WHERE id = ?", String.class, runId);
        Integer payslipCount =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payslips WHERE payroll_run_id = ?", Integer.class, runId);
        assertThat(status).isEqualTo("FAILED");
        assertThat(payslipCount).isZero();
    }

    @Test
    void creationIsUnauthenticatedReturns401() throws Exception {
        mockMvc.perform(post("/api/payroll-runs").contentType(MediaType.APPLICATION_JSON).content(createBody(1, YEAR)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void creationByEmployeeReturns403() throws Exception {
        mockMvc.perform(post("/api/payroll-runs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(1, YEAR)))
                .andExpect(status().isForbidden());
    }

    // ---- List ----

    @Test
    void hrAdminReceivesPaginatedResponseNewestCreatedFirst() throws Exception {
        long earlierId = PayrollTestFixtures.insertPayrollRunWithCreatedAt(
                jdbcTemplate, 4, YEAR, "CALCULATED", LocalDateTime.of(2026, 1, 1, 10, 0));
        long laterId = PayrollTestFixtures.insertPayrollRunWithCreatedAt(
                jdbcTemplate, 5, YEAR, "CALCULATED", LocalDateTime.of(2026, 1, 1, 11, 0));
        extraRunIds.add(earlierId);
        extraRunIds.add(laterId);

        mockMvc.perform(get("/api/payroll-runs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .param("year", String.valueOf(YEAR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(laterId))
                .andExpect(jsonPath("$.content[1].id").value(earlierId));
    }

    @Test
    void sameCreatedAtRowsUseIdDescAsDeterministicTiebreaker() throws Exception {
        LocalDateTime sameInstant = LocalDateTime.of(2026, 2, 1, 12, 0);
        long lowerId =
                PayrollTestFixtures.insertPayrollRunWithCreatedAt(jdbcTemplate, 6, YEAR, "CALCULATED", sameInstant);
        long higherId =
                PayrollTestFixtures.insertPayrollRunWithCreatedAt(jdbcTemplate, 7, YEAR, "CALCULATED", sameInstant);
        extraRunIds.add(lowerId);
        extraRunIds.add(higherId);

        mockMvc.perform(get("/api/payroll-runs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .param("year", String.valueOf(YEAR))
                        .param("status", "CALCULATED")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(higherId))
                .andExpect(jsonPath("$.content[1].id").value(lowerId));
    }

    @Test
    void statusFilterReturnsOnlyMatchingRuns() throws Exception {
        long calculatedId =
                PayrollTestFixtures.insertPayrollRunWithCreatedAt(jdbcTemplate, 8, YEAR, "CALCULATED", LocalDateTime.now());
        long failedId =
                PayrollTestFixtures.insertPayrollRunWithCreatedAt(jdbcTemplate, 9, YEAR, "FAILED", LocalDateTime.now());
        extraRunIds.add(calculatedId);
        extraRunIds.add(failedId);

        mockMvc.perform(get("/api/payroll-runs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .param("year", String.valueOf(YEAR))
                        .param("status", "FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(failedId));
    }

    @Test
    void yearFilterReturnsOnlyMatchingRuns() throws Exception {
        long thisYearId =
                PayrollTestFixtures.insertPayrollRunWithCreatedAt(jdbcTemplate, 10, YEAR, "CALCULATED", LocalDateTime.now());
        long otherYearId = PayrollTestFixtures.insertPayrollRunWithCreatedAt(
                jdbcTemplate, 1, OTHER_YEAR, "CALCULATED", LocalDateTime.now());
        extraRunIds.add(thisYearId);
        extraRunIds.add(otherYearId);

        mockMvc.perform(get("/api/payroll-runs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .param("year", String.valueOf(OTHER_YEAR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(otherYearId));
    }

    @Test
    void combinedStatusAndYearFiltersReturnOnlyMatchingRuns() throws Exception {
        long matches = PayrollTestFixtures.insertPayrollRunWithCreatedAt(
                jdbcTemplate, 11, YEAR, "FAILED", LocalDateTime.now());
        long wrongStatus = PayrollTestFixtures.insertPayrollRunWithCreatedAt(
                jdbcTemplate, 12, YEAR, "CALCULATED", LocalDateTime.now());
        long wrongYear = PayrollTestFixtures.insertPayrollRunWithCreatedAt(
                jdbcTemplate, 2, OTHER_YEAR, "FAILED", LocalDateTime.now());
        extraRunIds.add(matches);
        extraRunIds.add(wrongStatus);
        extraRunIds.add(wrongYear);

        mockMvc.perform(get("/api/payroll-runs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .param("year", String.valueOf(YEAR))
                        .param("status", "FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(matches));
    }

    @Test
    void filtersArePreservedAcrossPages() throws Exception {
        long r1 = PayrollTestFixtures.insertPayrollRunWithCreatedAt(
                jdbcTemplate, 3, OTHER_YEAR, "FAILED", LocalDateTime.of(2027, 1, 1, 1, 0));
        long r2 = PayrollTestFixtures.insertPayrollRunWithCreatedAt(
                jdbcTemplate, 4, OTHER_YEAR, "FAILED", LocalDateTime.of(2027, 1, 1, 2, 0));
        long r3 = PayrollTestFixtures.insertPayrollRunWithCreatedAt(
                jdbcTemplate, 5, OTHER_YEAR, "CALCULATED", LocalDateTime.of(2027, 1, 1, 3, 0));
        extraRunIds.add(r1);
        extraRunIds.add(r2);
        extraRunIds.add(r3);

        mockMvc.perform(get("/api/payroll-runs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .param("year", String.valueOf(OTHER_YEAR))
                        .param("status", "FAILED")
                        .param("size", "1")
                        .param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(r2));

        mockMvc.perform(get("/api/payroll-runs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .param("year", String.valueOf(OTHER_YEAR))
                        .param("status", "FAILED")
                        .param("size", "1")
                        .param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(r1));
    }

    @Test
    void unknownStatusValueReturns400() throws Exception {
        mockMvc.perform(get("/api/payroll-runs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .param("status", "NOT_A_REAL_STATUS"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidYearFilterReturns400() throws Exception {
        mockMvc.perform(get("/api/payroll-runs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .param("year", "1999"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listIsUnauthenticatedReturns401() throws Exception {
        mockMvc.perform(get("/api/payroll-runs")).andExpect(status().isUnauthorized());
    }

    @Test
    void listByEmployeeReturns403() throws Exception {
        mockMvc.perform(get("/api/payroll-runs").header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken))
                .andExpect(status().isForbidden());
    }

    // ---- Detail ----

    @Test
    void hrAdminReceivesCompleteRunDtoIncludingRateSnapshotsAndFailureReason() throws Exception {
        long failedId = PayrollTestFixtures.insertPayrollRunWithCreatedAt(
                jdbcTemplate, 6, OTHER_YEAR, "FAILED", LocalDateTime.now());
        extraRunIds.add(failedId);
        jdbcTemplate.update("UPDATE payroll_runs SET failure_reason = ? WHERE id = ?", "No eligible employees.", failedId);

        mockMvc.perform(get("/api/payroll-runs/" + failedId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("No eligible employees."))
                .andExpect(jsonPath("$.epfRateSnapshot").value(0.1100))
                .andExpect(jsonPath("$.socsoRateSnapshot").value(0.0050))
                .andExpect(jsonPath("$.eisRateSnapshot").value(0.0020))
                .andExpect(jsonPath("$.incomeTaxRateSnapshot").value(0.0500))
                .andExpect(jsonPath("$.version").exists());
    }

    @Test
    void unknownRunIdReturns404WithStableCode() throws Exception {
        mockMvc.perform(get("/api/payroll-runs/999999999").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYROLL_RUN_NOT_FOUND"));
    }

    @Test
    void detailIsUnauthenticatedReturns401() throws Exception {
        mockMvc.perform(get("/api/payroll-runs/1")).andExpect(status().isUnauthorized());
    }

    @Test
    void detailByEmployeeReturns403() throws Exception {
        mockMvc.perform(get("/api/payroll-runs/1").header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken))
                .andExpect(status().isForbidden());
    }

    // ---- Run payslip list ----

    @Test
    void onlyPayslipsBelongingToTheRequestedRunAreReturnedInDeterministicOrder() throws Exception {
        long runOneId =
                PayrollTestFixtures.insertPayrollRunWithCreatedAt(jdbcTemplate, 8, OTHER_YEAR, "CALCULATED", LocalDateTime.now());
        long runTwoId =
                PayrollTestFixtures.insertPayrollRunWithCreatedAt(jdbcTemplate, 9, OTHER_YEAR, "CALCULATED", LocalDateTime.now());
        extraRunIds.add(runOneId);
        extraRunIds.add(runTwoId);

        long empZ = PayrollTestFixtures.insertEmployee(jdbcTemplate, departmentId, "Z-PS", "000701010001");
        long empA = PayrollTestFixtures.insertEmployee(jdbcTemplate, departmentId, "A-PS", "000701010002");
        long empOther = PayrollTestFixtures.insertEmployee(jdbcTemplate, departmentId, "OTHER-PS", "000701010003");

        PayrollTestFixtures.insertPayslip(
                jdbcTemplate, runOneId, empZ, PayrollTestFixtures.EMPLOYEE_CODE_PREFIX + "Z-PS",
                new BigDecimal("1000.00"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("1000.00"),
                new BigDecimal("110.00"), new BigDecimal("5.00"), new BigDecimal("2.00"), new BigDecimal("50.00"),
                new BigDecimal("167.00"), new BigDecimal("833.00"));
        PayrollTestFixtures.insertPayslip(
                jdbcTemplate, runOneId, empA, PayrollTestFixtures.EMPLOYEE_CODE_PREFIX + "A-PS",
                new BigDecimal("2000.00"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("2000.00"),
                new BigDecimal("220.00"), new BigDecimal("10.00"), new BigDecimal("4.00"), new BigDecimal("100.00"),
                new BigDecimal("334.00"), new BigDecimal("1666.00"));
        PayrollTestFixtures.insertPayslip(
                jdbcTemplate, runTwoId, empOther, PayrollTestFixtures.EMPLOYEE_CODE_PREFIX + "OTHER-PS",
                new BigDecimal("3000.00"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("3000.00"),
                new BigDecimal("330.00"), new BigDecimal("15.00"), new BigDecimal("6.00"), new BigDecimal("150.00"),
                new BigDecimal("501.00"), new BigDecimal("2499.00"));

        mockMvc.perform(get("/api/payroll-runs/" + runOneId + "/payslips")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].employeeCodeSnapshot")
                        .value(PayrollTestFixtures.EMPLOYEE_CODE_PREFIX + "A-PS"))
                .andExpect(jsonPath("$.content[1].employeeCodeSnapshot")
                        .value(PayrollTestFixtures.EMPLOYEE_CODE_PREFIX + "Z-PS"))
                .andExpect(jsonPath("$.content[0].netPay").value(1666.00))
                .andExpect(jsonPath("$.content[0].pdfStatus").value("PENDING"))
                .andExpect(jsonPath("$.content[0].pdfPath").doesNotExist());
    }

    @Test
    void unknownRunIdForPayslipListReturns404NotEmptyPage() throws Exception {
        mockMvc.perform(get("/api/payroll-runs/999999999/payslips").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYROLL_RUN_NOT_FOUND"));
    }

    @Test
    void payslipListIsUnauthenticatedReturns401() throws Exception {
        mockMvc.perform(get("/api/payroll-runs/1/payslips")).andExpect(status().isUnauthorized());
    }

    @Test
    void payslipListByEmployeeReturns403() throws Exception {
        mockMvc.perform(get("/api/payroll-runs/1/payslips").header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken))
                .andExpect(status().isForbidden());
    }

    // ---- Retry (Phase 8B-2B) -- own reserved year within this class, distinct from every test above ----

    private static final int RETRY_YEAR = OTHER_YEAR + 1;

    @Test
    void hrAdminRetryOfAFailedRunSucceedsAndReturnsCalculated() throws Exception {
        int month = 1;
        mockMvc.perform(post("/api/payroll-runs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(month, RETRY_YEAR)))
                .andExpect(status().isUnprocessableEntity());
        Long runId = jdbcTemplate.queryForObject(
                "SELECT id FROM payroll_runs WHERE period_month = ? AND period_year = ?", Long.class, month, RETRY_YEAR);

        insertCandidateWithCoverage("RETRY1", month, new BigDecimal("1000.00"));

        mockMvc.perform(post("/api/payroll-runs/" + runId + "/retry").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(runId))
                .andExpect(jsonPath("$.status").value("CALCULATED"))
                .andExpect(jsonPath("$.failureReason").doesNotExist());
    }

    @Test
    void retryOfAnUnknownRunReturns404WithStableCode() throws Exception {
        mockMvc.perform(post("/api/payroll-runs/999999999/retry").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYROLL_RUN_NOT_FOUND"));
    }

    @Test
    void retryOfANonFailedRunReturns409WithStableCode() throws Exception {
        int month = 2;
        long calculatedRunId = PayrollTestFixtures.insertPayrollRunWithCreatedAt(
                jdbcTemplate, month, RETRY_YEAR, "CALCULATED", LocalDateTime.now());
        extraRunIds.add(calculatedRunId);

        mockMvc.perform(
                        post("/api/payroll-runs/" + calculatedRunId + "/retry").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYROLL_RUN_NOT_FAILED"))
                .andExpect(jsonPath("$.detail", org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Exception"))));
    }

    @Test
    void retryIsUnauthenticatedReturns401() throws Exception {
        mockMvc.perform(post("/api/payroll-runs/1/retry")).andExpect(status().isUnauthorized());
    }

    @Test
    void retryByEmployeeReturns403() throws Exception {
        mockMvc.perform(post("/api/payroll-runs/1/retry").header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken))
                .andExpect(status().isForbidden());
    }
}
