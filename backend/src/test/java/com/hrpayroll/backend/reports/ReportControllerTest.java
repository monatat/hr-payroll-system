package com.hrpayroll.backend.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hrpayroll.backend.security.jwt.JwtService;
import com.hrpayroll.backend.support.ReportTestFixtures;
import com.hrpayroll.backend.user.AccountStatus;
import com.hrpayroll.backend.user.User;
import com.hrpayroll.backend.user.UserRole;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Real-PostgreSQL, real-Spring-Security controller/integration tests for the Phase 10B HR_ADMIN
 * report endpoints. No {@code users} row is ever persisted for either test principal -- per
 * {@code JwtAuthenticationFilter}'s own Javadoc, authentication is resolved entirely from the
 * validated JWT's claims with no per-request database lookup, and {@link ReportController}/
 * {@link ReportService} never call {@code AuditService} (which is the only path that would ever
 * require an existing {@code users} row for a foreign key), so a transient, unpersisted
 * {@link User} is sufficient to build each token, exactly like
 * {@code LeaveBalanceControllerTest}'s employee-token precedent.
 *
 * <p>Two requirements from the approved Phase 10B test plan are proven at the service-unit level
 * instead, in {@link ReportServiceTest}, because they are not observable here: "no frontend-style
 * recomputation is performed" (the annual total and the department-summed total must come from
 * their own dedicated repository queries, never resummed in Java -- with consistent real data the
 * two would coincidentally agree, so only a mock that deliberately disagrees can prove which
 * source was actually used) and "empty headcount returns zero and an empty list" (headcount has no
 * scoping dimension like payroll's {@code year}, so the shared integration database's real ACTIVE
 * employees can never be forced to zero the way an unused year forces payroll cost to zero).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReportControllerTest {

    private static final int YEAR = 2081; // reserved for Phase 10B controller tests only
    private static final int OTHER_YEAR = 2082;
    private static final int EMPTY_YEAR = 2083;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtService jwtService;

    private long departmentId;
    private String adminToken;
    private String employeeToken;
    private final List<Long> createdRunIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ReportTestFixtures.cleanup(jdbcTemplate);
        createdRunIds.clear();
        departmentId = ReportTestFixtures.installDepartment(jdbcTemplate, "CTRL");
        long linkedEmployeeId = ReportTestFixtures.insertEmployee(jdbcTemplate, departmentId, "TOKEN", "800101-01-2001", "ACTIVE");

        adminToken = jwtService.generateAccessToken(
                new User(900_001L, "phase10b.admin@test.local", "hash", UserRole.HR_ADMIN, AccountStatus.ACTIVE, null));
        employeeToken = jwtService.generateAccessToken(new User(
                900_002L, "phase10b.employee@test.local", "hash", UserRole.EMPLOYEE, AccountStatus.ACTIVE, linkedEmployeeId));
    }

    @AfterEach
    void tearDown() {
        ReportTestFixtures.cleanup(jdbcTemplate);
        ReportTestFixtures.cleanupPayrollRuns(jdbcTemplate, createdRunIds);
    }

    private long newRun(int month, int year, String status) {
        long id = ReportTestFixtures.insertPayrollRun(jdbcTemplate, month, year, status);
        createdRunIds.add(id);
        return id;
    }

    private long newEmployee(String suffix, String ic, String status) {
        return ReportTestFixtures.insertEmployee(jdbcTemplate, departmentId, suffix, ic, status);
    }

    private long globalActiveEmployeeCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM employees WHERE employment_status = 'ACTIVE'", Long.class);
    }

    private long auditLogCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_logs", Long.class);
    }

    private long tableCount(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    // ---------------------------------------------------------------- authorization

    @Test
    void unauthenticatedPayrollCostRequestIsDenied() throws Exception {
        mockMvc.perform(get("/api/reports/payroll-cost").param("year", String.valueOf(YEAR)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unauthenticatedHeadcountRequestIsDenied() throws Exception {
        mockMvc.perform(get("/api/reports/headcount")).andExpect(status().isUnauthorized());
    }

    @Test
    void employeePayrollCostRequestReturns403() throws Exception {
        mockMvc.perform(get("/api/reports/payroll-cost")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken)
                        .param("year", String.valueOf(YEAR)))
                .andExpect(status().isForbidden());
    }

    @Test
    void employeeHeadcountRequestReturns403() throws Exception {
        mockMvc.perform(get("/api/reports/headcount").header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void hrAdminPayrollCostRequestSucceeds() throws Exception {
        mockMvc.perform(get("/api/reports/payroll-cost")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .param("year", String.valueOf(YEAR)))
                .andExpect(status().isOk());
    }

    @Test
    void hrAdminHeadcountRequestSucceeds() throws Exception {
        mockMvc.perform(get("/api/reports/headcount").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- payroll cost

    @Test
    void responseContainsTheRequestedYear() throws Exception {
        mockMvc.perform(get("/api/reports/payroll-cost")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .param("year", String.valueOf(YEAR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.year").value(YEAR));
    }

    @Test
    void annualTotalGrossPayIsExactRepositoryResult() throws Exception {
        long run = newRun(1, YEAR, "CALCULATED");
        long empA = newEmployee("A", "800101-01-2010", "ACTIVE");
        long empB = newEmployee("B", "800101-01-2011", "ACTIVE");
        ReportTestFixtures.insertPayslip(jdbcTemplate, run, empA, "EMP-PHASE10-A", new BigDecimal("4650.55"));
        ReportTestFixtures.insertPayslip(jdbcTemplate, run, empB, "EMP-PHASE10-B", new BigDecimal("3873.45"));

        mockMvc.perform(get("/api/reports/payroll-cost")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .param("year", String.valueOf(YEAR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.annualTotalGrossPay").value(8524.00));
    }

    @Test
    void monthlyTotalsContainExactMonthlyValues() throws Exception {
        long run = newRun(2, YEAR, "CALCULATED");
        long emp = newEmployee("C", "800101-01-2012", "ACTIVE");
        ReportTestFixtures.insertPayslip(jdbcTemplate, run, emp, "EMP-PHASE10-C", new BigDecimal("1000.00"));

        mockMvc.perform(get("/api/reports/payroll-cost")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .param("year", String.valueOf(YEAR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyTotals.length()").value(1))
                .andExpect(jsonPath("$.monthlyTotals[0].periodMonth").value(2))
                .andExpect(jsonPath("$.monthlyTotals[0].totalGrossPay").value(1000.00));
    }

    @Test
    void monthlyResultsAreOrderedAscending() throws Exception {
        long runMarch = newRun(3, YEAR, "CALCULATED");
        long empMarch = newEmployee("D", "800101-01-2013", "ACTIVE");
        ReportTestFixtures.insertPayslip(jdbcTemplate, runMarch, empMarch, "EMP-PHASE10-D", new BigDecimal("100.00"));
        long runJan = newRun(1, YEAR, "CALCULATED");
        long empJan = newEmployee("E", "800101-01-2014", "ACTIVE");
        ReportTestFixtures.insertPayslip(jdbcTemplate, runJan, empJan, "EMP-PHASE10-E", new BigDecimal("200.00"));

        mockMvc.perform(get("/api/reports/payroll-cost")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .param("year", String.valueOf(YEAR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyTotals[0].periodMonth").value(1))
                .andExpect(jsonPath("$.monthlyTotals[1].periodMonth").value(3));
    }

    @Test
    void calculatedRunContributes() throws Exception {
        long run = newRun(4, YEAR, "CALCULATED");
        long emp = newEmployee("F", "800101-01-2015", "ACTIVE");
        ReportTestFixtures.insertPayslip(jdbcTemplate, run, emp, "EMP-PHASE10-F", new BigDecimal("500.00"));

        mockMvc.perform(get("/api/reports/payroll-cost")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .param("year", String.valueOf(YEAR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.annualTotalGrossPay").value(500.00));
    }

    @Test
    void completedRunContributes() throws Exception {
        long run = newRun(5, YEAR, "COMPLETED");
        long emp = newEmployee("G", "800101-01-2016", "ACTIVE");
        ReportTestFixtures.insertPayslip(jdbcTemplate, run, emp, "EMP-PHASE10-G", new BigDecimal("600.00"));

        mockMvc.perform(get("/api/reports/payroll-cost")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .param("year", String.valueOf(YEAR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.annualTotalGrossPay").value(600.00));
    }

    @Test
    void processingRunDoesNotContribute() throws Exception {
        long run = newRun(6, YEAR, "PROCESSING");
        long emp = newEmployee("H", "800101-01-2017", "ACTIVE");
        // Deliberately anomalous: the real service never creates a payslip under a PROCESSING run.
        ReportTestFixtures.insertPayslip(jdbcTemplate, run, emp, "EMP-PHASE10-H", new BigDecimal("999999.99"));

        mockMvc.perform(get("/api/reports/payroll-cost")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .param("year", String.valueOf(YEAR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.annualTotalGrossPay").value(0))
                .andExpect(jsonPath("$.monthlyTotals.length()").value(0));
    }

    @Test
    void failedRunDoesNotContribute() throws Exception {
        long run = newRun(7, YEAR, "FAILED");
        long emp = newEmployee("I", "800101-01-2018", "ACTIVE");
        // Deliberately anomalous: the real service never creates a payslip under a FAILED run.
        ReportTestFixtures.insertPayslip(jdbcTemplate, run, emp, "EMP-PHASE10-I", new BigDecimal("999999.99"));

        mockMvc.perform(get("/api/reports/payroll-cost")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .param("year", String.valueOf(YEAR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.annualTotalGrossPay").value(0))
                .andExpect(jsonPath("$.monthlyTotals.length()").value(0));
    }

    @Test
    void anotherYearDoesNotContribute() throws Exception {
        long runThisYear = newRun(1, YEAR, "CALCULATED");
        long empThisYear = newEmployee("J", "800101-01-2019", "ACTIVE");
        ReportTestFixtures.insertPayslip(jdbcTemplate, runThisYear, empThisYear, "EMP-PHASE10-J", new BigDecimal("111.00"));
        long runOtherYear = newRun(1, OTHER_YEAR, "CALCULATED");
        long empOtherYear = newEmployee("K", "800101-01-2020", "ACTIVE");
        ReportTestFixtures.insertPayslip(jdbcTemplate, runOtherYear, empOtherYear, "EMP-PHASE10-K", new BigDecimal("222.00"));

        mockMvc.perform(get("/api/reports/payroll-cost")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .param("year", String.valueOf(YEAR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.annualTotalGrossPay").value(111.00));
    }

    @Test
    void emptyYearReturnsNumericZeroAndAnEmptyList() throws Exception {
        mockMvc.perform(get("/api/reports/payroll-cost")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .param("year", String.valueOf(EMPTY_YEAR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.annualTotalGrossPay").value(0))
                .andExpect(jsonPath("$.monthlyTotals").isArray())
                .andExpect(jsonPath("$.monthlyTotals.length()").value(0));
    }

    @Test
    void decimalCentPrecisionIsExact() throws Exception {
        long run = newRun(8, YEAR, "CALCULATED");
        long empA = newEmployee("L", "800101-01-2021", "ACTIVE");
        long empB = newEmployee("M", "800101-01-2022", "ACTIVE");
        ReportTestFixtures.insertPayslip(jdbcTemplate, run, empA, "EMP-PHASE10-L", new BigDecimal("0.01"));
        ReportTestFixtures.insertPayslip(jdbcTemplate, run, empB, "EMP-PHASE10-M", new BigDecimal("0.02"));

        mockMvc.perform(get("/api/reports/payroll-cost")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .param("year", String.valueOf(YEAR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.annualTotalGrossPay").value(0.03));
    }

    @Test
    void payrollCostResponseContainsNoEmployeeIdentityOrCompensationField() throws Exception {
        long run = newRun(9, YEAR, "CALCULATED");
        long emp = newEmployee("N", "800101-01-2023", "ACTIVE");
        ReportTestFixtures.insertPayslip(jdbcTemplate, run, emp, "EMP-PHASE10-N", new BigDecimal("700.00"));

        String body = mockMvc.perform(get("/api/reports/payroll-cost")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .param("year", String.valueOf(YEAR)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .doesNotContainIgnoringCase("employeeId")
                .doesNotContainIgnoringCase("employeeCode")
                .doesNotContainIgnoringCase("icNumber")
                .doesNotContainIgnoringCase("basePay")
                .doesNotContainIgnoringCase("netPay")
                .doesNotContainIgnoringCase("payrollRunId")
                .doesNotContainIgnoringCase("payslip");
    }

    @Test
    void payrollCostResponseRetainsApprovedAggregateTotalGrossPayField() throws Exception {
        mockMvc.perform(get("/api/reports/payroll-cost")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .param("year", String.valueOf(EMPTY_YEAR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.annualTotalGrossPay").exists());
    }

    // ---------------------------------------------------------------- headcount

    @Test
    void totalActiveEmployeesIsExact() throws Exception {
        long before = globalActiveEmployeeCount();
        newEmployee("HC1", "800101-01-3001", "ACTIVE");
        newEmployee("HC2", "800101-01-3002", "ACTIVE");

        mockMvc.perform(get("/api/reports/headcount").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalActiveEmployees").value(before + 2));
    }

    @Test
    void activeEmployeesContribute() throws Exception {
        long before = globalActiveEmployeeCount();
        newEmployee("HC3", "800101-01-3003", "ACTIVE");

        mockMvc.perform(get("/api/reports/headcount").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalActiveEmployees").value(before + 1));
    }

    @Test
    void inactiveEmployeesDoNotContribute() throws Exception {
        long before = globalActiveEmployeeCount();
        newEmployee("HC4", "800101-01-3004", "INACTIVE");

        mockMvc.perform(get("/api/reports/headcount").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalActiveEmployees").value(before));
    }

    @Test
    void departmentGroupsAreExact() throws Exception {
        long dept = ReportTestFixtures.installDepartment(jdbcTemplate, "GROUPS");
        ReportTestFixtures.insertEmployee(jdbcTemplate, dept, "GRP1", "800101-01-3010", "ACTIVE");
        ReportTestFixtures.insertEmployee(jdbcTemplate, dept, "GRP2", "800101-01-3011", "ACTIVE");
        ReportTestFixtures.insertEmployee(jdbcTemplate, dept, "GRP3", "800101-01-3012", "ACTIVE");

        mockMvc.perform(get("/api/reports/headcount").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.departments[?(@.departmentId == " + dept + ")].activeEmployeeCount").value(3));
    }

    @Test
    void departmentOrderingIsDeterministic() throws Exception {
        long deptZ = ReportTestFixtures.installDepartment(jdbcTemplate, "ZZZ-ORDER");
        long deptA = ReportTestFixtures.installDepartment(jdbcTemplate, "AAA-ORDER");
        ReportTestFixtures.insertEmployee(jdbcTemplate, deptZ, "ORDZ", "800101-01-3020", "ACTIVE");
        ReportTestFixtures.insertEmployee(jdbcTemplate, deptA, "ORDA", "800101-01-3021", "ACTIVE");

        String body = mockMvc.perform(get("/api/reports/headcount").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        int indexOfA = body.indexOf("\"departmentId\":" + deptA);
        int indexOfZ = body.indexOf("\"departmentId\":" + deptZ);
        assertThat(indexOfA).isGreaterThanOrEqualTo(0);
        assertThat(indexOfZ).isGreaterThanOrEqualTo(0);
        assertThat(indexOfA).isLessThan(indexOfZ);
    }

    @Test
    void departmentWithOnlyInactiveEmployeesIsAbsent() throws Exception {
        long deptInactiveOnly = ReportTestFixtures.installDepartment(jdbcTemplate, "INACTIVE-ONLY");
        ReportTestFixtures.insertEmployee(jdbcTemplate, deptInactiveOnly, "IN1", "800101-01-3030", "INACTIVE");

        mockMvc.perform(get("/api/reports/headcount").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.departments[?(@.departmentId == " + deptInactiveOnly + ")]").isEmpty());
    }

    @Test
    void headcountResponseContainsNoEmployeeLevelRowOrCompensationField() throws Exception {
        long dept = ReportTestFixtures.installDepartment(jdbcTemplate, "NOLEAK");
        ReportTestFixtures.insertEmployee(jdbcTemplate, dept, "NOLEAK1", "800101-01-3040", "ACTIVE");

        String body = mockMvc.perform(get("/api/reports/headcount").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .doesNotContainIgnoringCase("employeeId")
                .doesNotContainIgnoringCase("employeeCode")
                .doesNotContainIgnoringCase("icNumber")
                .doesNotContainIgnoringCase("fullName")
                .doesNotContainIgnoringCase("basePay")
                .doesNotContainIgnoringCase("grossPay")
                .doesNotContainIgnoringCase("netPay")
                .doesNotContainIgnoringCase("salary");
    }

    // ---------------------------------------------------------------- validation and safety

    @Test
    void missingYearReturnsSafe400() throws Exception {
        String body = mockMvc.perform(get("/api/reports/payroll-cost").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertUnsafeContentAbsent(body);
    }

    @Test
    void nonNumericYearReturnsSafe400() throws Exception {
        String body = mockMvc.perform(get("/api/reports/payroll-cost")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .param("year", "not-a-year"))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertUnsafeContentAbsent(body);
    }

    @Test
    void yearBelowRangeReturnsSafe400() throws Exception {
        String body = mockMvc.perform(get("/api/reports/payroll-cost")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .param("year", "1999"))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertUnsafeContentAbsent(body);
    }

    @Test
    void yearAboveRangeReturnsSafe400WithFixedProblemDetailShape() throws Exception {
        String body = mockMvc.perform(get("/api/reports/payroll-cost")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .param("year", "2101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").exists())
                .andExpect(jsonPath("$.detail").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertUnsafeContentAbsent(body);
    }

    private static void assertUnsafeContentAbsent(String body) {
        assertThat(body)
                .doesNotContain("Exception")
                .doesNotContain("SELECT")
                .doesNotContain("at com.hrpayroll")
                .doesNotContainIgnoringCase("java.lang")
                .doesNotContainIgnoringCase("stacktrace");
    }

    @Test
    void noSuccessfulReportGetCreatesAnAuditLogRow() throws Exception {
        long before = auditLogCount();

        mockMvc.perform(get("/api/reports/payroll-cost")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .param("year", String.valueOf(YEAR)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/reports/headcount").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        assertThat(auditLogCount()).isEqualTo(before);
    }

    @Test
    void noFailedOrForbiddenReportGetCreatesAnAuditLogRow() throws Exception {
        long before = auditLogCount();

        mockMvc.perform(get("/api/reports/payroll-cost")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken)
                        .param("year", String.valueOf(YEAR)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/reports/headcount").header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/reports/headcount")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/reports/payroll-cost").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isBadRequest());

        assertThat(auditLogCount()).isEqualTo(before);
    }

    @Test
    void reportRequestsDoNotMutateAnyRow() throws Exception {
        long payrollRuns = tableCount("payroll_runs");
        long payslips = tableCount("payslips");
        long employees = tableCount("employees");
        long departments = tableCount("departments");
        long users = tableCount("users");

        mockMvc.perform(get("/api/reports/payroll-cost")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .param("year", String.valueOf(YEAR)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/reports/headcount").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/reports/headcount").header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken))
                .andExpect(status().isForbidden());

        assertThat(tableCount("payroll_runs")).isEqualTo(payrollRuns);
        assertThat(tableCount("payslips")).isEqualTo(payslips);
        assertThat(tableCount("employees")).isEqualTo(employees);
        assertThat(tableCount("departments")).isEqualTo(departments);
        assertThat(tableCount("users")).isEqualTo(users);
    }

    @Test
    void responseListsSerializeAsEmptyArraysRatherThanNull() throws Exception {
        mockMvc.perform(get("/api/reports/payroll-cost")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .param("year", String.valueOf(EMPTY_YEAR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyTotals").isArray());

        mockMvc.perform(get("/api/reports/headcount").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.departments").isArray());
    }

    @Test
    void httpResponsesDoNotExposeRepositoryProjectionClassNames() throws Exception {
        String payrollBody = mockMvc.perform(get("/api/reports/payroll-cost")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .param("year", String.valueOf(YEAR)))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String headcountBody = mockMvc.perform(get("/api/reports/headcount").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(payrollBody).doesNotContain("MonthlyPayrollCostSummary").doesNotContain("com.hrpayroll");
        assertThat(headcountBody).doesNotContain("DepartmentHeadcountSummary").doesNotContain("com.hrpayroll");
    }

    @Test
    void extraClientSuppliedIdentityParametersAreIgnored() throws Exception {
        long run = newRun(12, YEAR, "CALCULATED");
        long emp = newEmployee("Z", "800101-01-2099", "ACTIVE");
        ReportTestFixtures.insertPayslip(jdbcTemplate, run, emp, "EMP-PHASE10-Z", new BigDecimal("321.00"));

        mockMvc.perform(get("/api/reports/payroll-cost")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .param("year", String.valueOf(YEAR))
                        .param("employeeId", "999999")
                        .param("userId", "888888")
                        .param("departmentId", String.valueOf(departmentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.annualTotalGrossPay").value(321.00));
    }
}
