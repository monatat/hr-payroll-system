package com.hrpayroll.backend.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hrpayroll.backend.department.DepartmentRepository;
import com.hrpayroll.backend.employee.EmployeeRepository;
import com.hrpayroll.backend.leave.LeaveBalanceRepository;
import com.hrpayroll.backend.leave.LeaveRequestRepository;
import com.hrpayroll.backend.payroll.PayrollRunRepository;
import com.hrpayroll.backend.payroll.PayslipRepository;
import com.hrpayroll.backend.salary.SalaryStructureRepository;
import com.hrpayroll.backend.user.UserRepository;
import jakarta.persistence.EntityManager;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Real-PostgreSQL integration tests for {@link DemoDataSeeder} (Phase 12C-2).
 *
 * <p>These tests run under the ordinary {@code test} profile -- the demo runner/seeder beans do not
 * exist there -- and construct the seeder directly with a fixed Kuala Lumpur {@link Clock} and a
 * distinct test-only {@link DemoIdentities} set ({@code TESTDEMO...}), so they never collide with (or
 * create) the real {@code DEMO...} dataset in the shared development database. Every row they create is
 * removed by exact id in FK-safe order before and after each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DemoDataSeederTest {

    private static final ZoneId KL = ZoneId.of("Asia/Kuala_Lumpur");

    static final DemoIdentities TEST_IDENTITIES = new DemoIdentities(
            "testdemo.admin@demo.local",
            "testdemo.employee@demo.local",
            "TESTDEMO Engineering",
            "TESTDEMO Finance",
            "TESTDEMO-001",
            "TESTDEMO Employee One",
            "TESTDEMO-IC-0001",
            "TESTDEMO-002",
            "TESTDEMO Employee Two",
            "TESTDEMO-IC-0002");

    private static final String ADMIN_PASSWORD = "TestDemoAdmin-Pass-1!";
    private static final String EMPLOYEE_PASSWORD = "TestDemoEmployee-Pass-2!";

    private static final String[] COUNTED_TABLES = {
        "departments", "employees", "users", "salary_structures", "leave_balances", "leave_requests",
        "payroll_runs", "payslips", "attendance", "audit_logs"
    };

    @Autowired private ApplicationContext applicationContext;
    @Autowired private EntityManager entityManager;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private SalaryStructureRepository salaryStructureRepository;
    @Autowired private LeaveBalanceRepository leaveBalanceRepository;
    @Autowired private LeaveRequestRepository leaveRequestRepository;
    @Autowired private PayrollRunRepository payrollRunRepository;
    @Autowired private PayslipRepository payslipRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mockMvc;

    /** Ids of deliberately-conflicting rows a test inserts itself, removed by exact id in cleanup. */
    private final List<Long> extraRunIds = new ArrayList<>();
    private final List<Long> extraUserIds = new ArrayList<>();
    private final List<Long> extraEmployeeIds = new ArrayList<>();
    private final List<Long> extraDepartmentIds = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanUp() {
        List<Long> employeeIds = ids(
                "SELECT id FROM employees WHERE employee_code IN (?, ?) OR ic_number IN (?, ?)",
                TEST_IDENTITIES.employeeOneCode(), TEST_IDENTITIES.employeeTwoCode(),
                TEST_IDENTITIES.employeeOneIc(), TEST_IDENTITIES.employeeTwoIc());
        employeeIds.addAll(extraEmployeeIds);
        List<Long> userIds = ids("SELECT id FROM users WHERE email IN (?, ?)",
                TEST_IDENTITIES.adminEmail(), TEST_IDENTITIES.employeeEmail());
        userIds.addAll(extraUserIds);
        List<Long> departmentIds = ids("SELECT id FROM departments WHERE name IN (?, ?)",
                TEST_IDENTITIES.engineeringDepartment(), TEST_IDENTITIES.financeDepartment());
        departmentIds.addAll(extraDepartmentIds);
        List<Long> runIds = new ArrayList<>(extraRunIds);
        if (!employeeIds.isEmpty()) {
            runIds.addAll(ids("SELECT DISTINCT payroll_run_id FROM payslips WHERE employee_id IN (" + in(employeeIds) + ")",
                    employeeIds.toArray()));
        }

        deleteByIds("DELETE FROM audit_logs WHERE entity_name = 'USER' AND entity_id IN (%s)", userIds);
        deleteByIds("DELETE FROM payslips WHERE employee_id IN (%s)", employeeIds);
        deleteByIds("DELETE FROM payslips WHERE payroll_run_id IN (%s)", runIds);
        deleteByIds("DELETE FROM payroll_runs WHERE id IN (%s)", runIds);
        deleteByIds("DELETE FROM leave_requests WHERE employee_id IN (%s)", employeeIds);
        deleteByIds("DELETE FROM leave_balances WHERE employee_id IN (%s)", employeeIds);
        deleteByIds("DELETE FROM salary_structures WHERE employee_id IN (%s)", employeeIds);
        deleteByIds("DELETE FROM attendance WHERE employee_id IN (%s)", employeeIds);
        deleteByIds("DELETE FROM users WHERE id IN (%s)", userIds);
        deleteByIds("DELETE FROM employees WHERE id IN (%s)", employeeIds);
        deleteByIds("DELETE FROM departments WHERE id IN (%s)", departmentIds);

        extraRunIds.clear();
        extraUserIds.clear();
        extraEmployeeIds.clear();
        extraDepartmentIds.clear();
    }

    // ---------------------------------------------------------------- activation / wiring

    @Test
    void demoBeansAreAbsentFromTheOrdinaryTestContext() {
        assertThat(applicationContext.getBeanNamesForType(DemoDataRunner.class)).isEmpty();
        assertThat(applicationContext.getBeanNamesForType(DemoDataSeeder.class)).isEmpty();
    }

    @Test
    void seederHasNoAuditPdfOrDomainServiceDependency() {
        List<String> fieldTypes = Arrays.stream(DemoDataSeeder.class.getDeclaredFields())
                .map(Field::getType)
                .map(Class::getSimpleName)
                .toList();

        assertThat(fieldTypes).noneMatch(name -> name.contains("Audit"));
        assertThat(fieldTypes).noneMatch(name -> name.contains("Pdf"));
        assertThat(fieldTypes).noneMatch(name -> name.endsWith("Service"));
    }

    // ---------------------------------------------------------------- first seed

    @Test
    void firstSeedCreatesExactlyFifteenRowsAndNoAttendanceOrAuditRows() {
        Map<String, Long> before = counts();
        Map<String, String> preExistingChecksums = preExistingChecksums();

        DemoDataSeeder.SeedOutcome outcome = seeder(march2093()).seed(ADMIN_PASSWORD, EMPLOYEE_PASSWORD);

        assertThat(outcome).isEqualTo(DemoDataSeeder.SeedOutcome.CREATED);
        Map<String, Long> after = counts();
        assertThat(after.get("departments") - before.get("departments")).isEqualTo(2);
        assertThat(after.get("employees") - before.get("employees")).isEqualTo(2);
        assertThat(after.get("users") - before.get("users")).isEqualTo(2);
        assertThat(after.get("salary_structures") - before.get("salary_structures")).isEqualTo(2);
        assertThat(after.get("leave_balances") - before.get("leave_balances")).isEqualTo(2);
        assertThat(after.get("leave_requests") - before.get("leave_requests")).isEqualTo(2);
        assertThat(after.get("payroll_runs") - before.get("payroll_runs")).isEqualTo(1);
        assertThat(after.get("payslips") - before.get("payslips")).isEqualTo(2);
        assertThat(after.get("attendance")).isEqualTo(before.get("attendance"));
        assertThat(after.get("audit_logs")).isEqualTo(before.get("audit_logs"));
        assertThat(preExistingChecksums()).isEqualTo(preExistingChecksums);
    }

    @Test
    void seedsTheExactIdentitiesRolesStatusesAndRelationships() {
        seeder(march2093()).seed(ADMIN_PASSWORD, EMPLOYEE_PASSWORD);

        assertThat(jdbc.queryForList(
                "SELECT e.employee_code, e.full_name, e.ic_number, e.employment_status, e.job_title, d.name AS department, "
                        + "e.hire_date::text AS hire_date, e.phone_number, e.bank_name, e.bank_account_no "
                        + "FROM employees e JOIN departments d ON d.id = e.department_id "
                        + "WHERE e.employee_code IN (?, ?) ORDER BY e.employee_code",
                TEST_IDENTITIES.employeeOneCode(), TEST_IDENTITIES.employeeTwoCode()))
                .containsExactly(
                        row("employee_code", "TESTDEMO-001", "full_name", "TESTDEMO Employee One", "ic_number", "TESTDEMO-IC-0001",
                                "employment_status", "ACTIVE", "job_title", "Software Engineer", "department", "TESTDEMO Engineering",
                                "hire_date", "2091-03-18", "phone_number", null, "bank_name", null, "bank_account_no", null),
                        row("employee_code", "TESTDEMO-002", "full_name", "TESTDEMO Employee Two", "ic_number", "TESTDEMO-IC-0002",
                                "employment_status", "ACTIVE", "job_title", "Accountant", "department", "TESTDEMO Finance",
                                "hire_date", "2092-03-18", "phone_number", null, "bank_name", null, "bank_account_no", null));

        assertThat(jdbc.queryForList(
                "SELECT u.email, u.role, u.account_status, e.employee_code FROM users u "
                        + "LEFT JOIN employees e ON e.id = u.employee_id WHERE u.email IN (?, ?) ORDER BY u.email",
                TEST_IDENTITIES.adminEmail(), TEST_IDENTITIES.employeeEmail()))
                .containsExactly(
                        row("email", "testdemo.admin@demo.local", "role", "HR_ADMIN", "account_status", "ACTIVE", "employee_code", null),
                        row("email", "testdemo.employee@demo.local", "role", "EMPLOYEE", "account_status", "ACTIVE",
                                "employee_code", "TESTDEMO-001"));
    }

    @Test
    void seedsExactSalaryBalanceAndPayslipValues() {
        seeder(march2093()).seed(ADMIN_PASSWORD, EMPLOYEE_PASSWORD);

        assertThat(jdbc.queryForList(
                "SELECT e.employee_code, s.base_pay::text AS base, s.housing_allowance::text AS housing, "
                        + "s.transport_allowance::text AS transport, s.effective_from::text AS effective_from, s.effective_to "
                        + "FROM salary_structures s JOIN employees e ON e.id = s.employee_id "
                        + "WHERE e.employee_code IN (?, ?) ORDER BY e.employee_code",
                TEST_IDENTITIES.employeeOneCode(), TEST_IDENTITIES.employeeTwoCode()))
                .containsExactly(
                        row("employee_code", "TESTDEMO-001", "base", "4200.00", "housing", "400.00", "transport", "150.00",
                                "effective_from", "2091-03-18", "effective_to", null),
                        row("employee_code", "TESTDEMO-002", "base", "5200.00", "housing", "500.00", "transport", "200.00",
                                "effective_from", "2092-03-18", "effective_to", null));

        assertThat(jdbc.queryForList(
                "SELECT t.name, b.year, b.entitled_days::text AS entitled, b.used_days::text AS used, "
                        + "(b.entitled_days - b.used_days)::text AS remaining "
                        + "FROM leave_balances b JOIN leave_types t ON t.id = b.leave_type_id JOIN employees e ON e.id = b.employee_id "
                        + "WHERE e.employee_code = ? ORDER BY t.name",
                TEST_IDENTITIES.employeeOneCode()))
                .containsExactly(
                        row("name", "Annual Leave", "year", 2093, "entitled", "14.0", "used", "2.0", "remaining", "12.0"),
                        row("name", "Sick Leave", "year", 2093, "entitled", "14.0", "used", "0.0", "remaining", "14.0"));

        assertThat(jdbc.queryForList(
                "SELECT p.employee_code_snapshot, p.employee_name_snapshot, p.department_name_snapshot, p.job_title_snapshot, "
                        + "p.base_pay::text AS base, p.housing_allowance::text AS housing, p.transport_allowance::text AS transport, "
                        + "p.gross_pay::text AS gross, p.epf_deduction::text AS epf, p.socso_deduction::text AS socso, "
                        + "p.eis_deduction::text AS eis, p.income_tax::text AS tax, p.total_deductions::text AS total, "
                        + "p.net_pay::text AS net, p.pdf_status, p.pdf_path "
                        + "FROM payslips p JOIN employees e ON e.id = p.employee_id "
                        + "WHERE e.employee_code IN (?, ?) ORDER BY e.employee_code",
                TEST_IDENTITIES.employeeOneCode(), TEST_IDENTITIES.employeeTwoCode()))
                .containsExactly(
                        row("employee_code_snapshot", "TESTDEMO-001", "employee_name_snapshot", "TESTDEMO Employee One",
                                "department_name_snapshot", "TESTDEMO Engineering", "job_title_snapshot", "Software Engineer",
                                "base", "4200.00", "housing", "400.00", "transport", "150.00", "gross", "4750.00",
                                "epf", "522.50", "socso", "23.75", "eis", "9.50", "tax", "237.50", "total", "793.25",
                                "net", "3956.75", "pdf_status", "PENDING", "pdf_path", null),
                        row("employee_code_snapshot", "TESTDEMO-002", "employee_name_snapshot", "TESTDEMO Employee Two",
                                "department_name_snapshot", "TESTDEMO Finance", "job_title_snapshot", "Accountant",
                                "base", "5200.00", "housing", "500.00", "transport", "200.00", "gross", "5900.00",
                                "epf", "649.00", "socso", "29.50", "eis", "11.80", "tax", "295.00", "total", "985.30",
                                "net", "4914.70", "pdf_status", "PENDING", "pdf_path", null));

        assertThat(jdbc.queryForObject(
                "SELECT SUM(p.gross_pay)::text FROM payslips p JOIN employees e ON e.id = p.employee_id WHERE e.employee_code IN (?, ?)",
                String.class, TEST_IDENTITIES.employeeOneCode(), TEST_IDENTITIES.employeeTwoCode()))
                .isEqualTo("10650.00");
    }

    @Test
    void seedsACalculatedPayrollRunForThePreviousMonthWithDefaultRates() {
        seeder(march2093()).seed(ADMIN_PASSWORD, EMPLOYEE_PASSWORD);

        List<Map<String, Object>> runs = jdbc.queryForList(
                "SELECT DISTINCT r.period_month, r.period_year, r.period_start::text AS period_start, r.period_end::text AS period_end, "
                        + "r.status, r.failure_reason, r.epf_rate_snapshot::text AS epf, r.socso_rate_snapshot::text AS socso, "
                        + "r.eis_rate_snapshot::text AS eis, r.income_tax_rate_snapshot::text AS tax "
                        + "FROM payroll_runs r JOIN payslips p ON p.payroll_run_id = r.id JOIN employees e ON e.id = p.employee_id "
                        + "WHERE e.employee_code IN (?, ?)",
                TEST_IDENTITIES.employeeOneCode(), TEST_IDENTITIES.employeeTwoCode());

        assertThat(runs).containsExactly(row("period_month", 2, "period_year", 2093, "period_start", "2093-02-01",
                "period_end", "2093-02-28", "status", "CALCULATED", "failure_reason", null,
                "epf", "0.1100", "socso", "0.0050", "eis", "0.0020", "tax", "0.0500"));
    }

    @Test
    void seedsDeterministicNonOverlappingWeekdayLeaveRequestsInTheBalanceYear() {
        seeder(march2093()).seed(ADMIN_PASSWORD, EMPLOYEE_PASSWORD);

        Long adminId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, TEST_IDENTITIES.adminEmail());
        assertThat(jdbc.queryForList(
                "SELECT t.name, r.start_date::text AS start_date, r.end_date::text AS end_date, r.days_requested::text AS days, "
                        + "r.duration_type, r.status, r.reviewed_by, r.reviewed_at::text AS reviewed_at, r.rejection_reason "
                        + "FROM leave_requests r JOIN leave_types t ON t.id = r.leave_type_id JOIN employees e ON e.id = r.employee_id "
                        + "WHERE e.employee_code = ? ORDER BY r.start_date",
                TEST_IDENTITIES.employeeOneCode()))
                .containsExactly(
                        row("name", "Annual Leave", "start_date", "2093-03-16", "end_date", "2093-03-17", "days", "2.0",
                                "duration_type", "FULL_DAY", "status", "APPROVED", "reviewed_by", adminId,
                                "reviewed_at", "2093-03-18 10:00:00", "rejection_reason", null),
                        row("name", "Annual Leave", "start_date", "2093-03-23", "end_date", "2093-03-25", "days", "3.0",
                                "duration_type", "FULL_DAY", "status", "PENDING", "reviewed_by", null,
                                "reviewed_at", null, "rejection_reason", null));
    }

    @Test
    void leavePlanKeepsWeekdayNonOverlappingRequestsInOneYearAcrossYearBoundaries() {
        assertPlan(LocalDate.of(2093, 3, 18), 2093, "2093-03-16", "2093-03-17", "2093-03-23", "2093-03-25");
        assertPlan(LocalDate.of(2093, 12, 31), 2093, "2093-12-21", "2093-12-22", "2093-12-28", "2093-12-30");
        assertPlan(LocalDate.of(2094, 1, 1), 2094, "2094-01-04", "2094-01-05", "2094-01-11", "2094-01-13");

        for (LocalDate date = LocalDate.of(2093, 12, 20); !date.isAfter(LocalDate.of(2094, 1, 12)); date = date.plusDays(1)) {
            DemoDataSeeder.LeavePlan plan = DemoDataSeeder.LeavePlan.forDate(date);
            assertThat(plan.year()).isEqualTo(date.getYear());
            for (LocalDate day : List.of(plan.approvedStart(), plan.approvedEnd(), plan.pendingStart(), plan.pendingEnd())) {
                assertThat(day.getYear()).isEqualTo(plan.year());
                assertThat(day.getDayOfWeek()).isNotIn(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
            }
            assertThat(plan.approvedStart().getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
            assertThat(plan.approvedEnd()).isEqualTo(plan.approvedStart().plusDays(1));
            assertThat(plan.pendingStart().getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
            assertThat(plan.pendingEnd()).isEqualTo(plan.pendingStart().plusDays(2));
            assertThat(plan.pendingStart()).isAfter(plan.approvedEnd());
        }
    }

    // ---------------------------------------------------------------- passwords / login

    @Test
    void seededPasswordsAreBcryptAndAuthenticateThroughNormalLoginWithoutLeakingSecrets() throws Exception {
        seeder(march2093()).seed(ADMIN_PASSWORD, EMPLOYEE_PASSWORD);

        List<String> hashes = jdbc.queryForList("SELECT password_hash FROM users WHERE email IN (?, ?)", String.class,
                TEST_IDENTITIES.adminEmail(), TEST_IDENTITIES.employeeEmail());
        assertThat(hashes).hasSize(2).allMatch(hash -> hash.startsWith("$2a$10$"));
        assertThat(hashes).noneMatch(hash -> hash.contains(ADMIN_PASSWORD) || hash.contains(EMPLOYEE_PASSWORD));

        for (String[] credentials : new String[][] {
            {TEST_IDENTITIES.adminEmail(), ADMIN_PASSWORD}, {TEST_IDENTITIES.employeeEmail(), EMPLOYEE_PASSWORD}
        }) {
            String body = mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + credentials[0] + "\",\"password\":\"" + credentials[1] + "\"}"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).contains("accessToken").doesNotContain(credentials[1]);
            assertThat(hashes).allSatisfy(hash -> assertThat(body).doesNotContain(hash));
        }

        List<String> auditDetails = jdbc.queryForList(
                "SELECT details::text FROM audit_logs WHERE entity_name = 'USER' AND entity_id IN "
                        + "(SELECT id FROM users WHERE email IN (?, ?))",
                String.class, TEST_IDENTITIES.adminEmail(), TEST_IDENTITIES.employeeEmail());
        assertThat(auditDetails).isNotEmpty();
        assertThat(auditDetails).noneMatch(details -> details.contains(ADMIN_PASSWORD) || details.contains(EMPLOYEE_PASSWORD));
        assertThat(auditDetails).noneMatch(details -> hashes.stream().anyMatch(details::contains));
    }

    // ---------------------------------------------------------------- idempotency

    @Test
    void secondSeedCreatesNothingAndPreservesEveryValueEvenOnALaterDate() {
        seeder(march2093()).seed(ADMIN_PASSWORD, EMPLOYEE_PASSWORD);
        Map<String, Long> countsAfterFirst = counts();
        String snapshot = testRowSnapshot();

        Clock laterClock = clockAt(LocalDateTime.of(2093, 5, 4, 9, 30));
        DemoDataSeeder.SeedOutcome outcome = seeder(laterClock).seed("Different-Admin-Pass-3!", "Different-Employee-Pass-4!");

        assertThat(outcome).isEqualTo(DemoDataSeeder.SeedOutcome.ALREADY_PRESENT);
        assertThat(counts()).isEqualTo(countsAfterFirst);
        assertThat(testRowSnapshot()).isEqualTo(snapshot);
    }

    // ---------------------------------------------------------------- conflicts / partial data

    @Test
    void partialDatasetWithOnlyAReservedDepartmentFailsSafely() {
        extraDepartmentIds.add(insertDepartment(TEST_IDENTITIES.financeDepartment()));

        assertConflictChangesNothing(march2093());
    }

    @Test
    void partialDatasetMissingASalaryStructureFailsSafely() {
        seeder(march2093()).seed(ADMIN_PASSWORD, EMPLOYEE_PASSWORD);
        jdbc.update("DELETE FROM salary_structures WHERE employee_id = (SELECT id FROM employees WHERE employee_code = ?)",
                TEST_IDENTITIES.employeeTwoCode());

        assertConflictChangesNothing(march2093());
    }

    @Test
    void conflictingReservedEmailFailsSafely() {
        extraUserIds.add(jdbc.queryForObject(
                "INSERT INTO users (email, password_hash, role, account_status, employee_id) "
                        + "VALUES (?, 'not-a-real-hash', 'HR_ADMIN', 'ACTIVE', NULL) RETURNING id",
                Long.class, TEST_IDENTITIES.employeeEmail()));

        assertConflictChangesNothing(march2093());
    }

    @Test
    void conflictingEmployeeCodeFailsSafely() {
        long departmentId = insertDepartment("TESTDEMO-X Unrelated Department");
        extraDepartmentIds.add(departmentId);
        extraEmployeeIds.add(insertEmployee(TEST_IDENTITIES.employeeOneCode(), "TESTDEMO-X-IC-1", departmentId));

        assertConflictChangesNothing(march2093());
    }

    @Test
    void conflictingIcNumberFailsSafely() {
        long departmentId = insertDepartment("TESTDEMO-X Unrelated Department");
        extraDepartmentIds.add(departmentId);
        extraEmployeeIds.add(insertEmployee("TESTDEMO-X-1", TEST_IDENTITIES.employeeTwoIc(), departmentId));

        assertConflictChangesNothing(march2093());
    }

    @Test
    void conflictingDepartmentNameFailsSafely() {
        extraDepartmentIds.add(insertDepartment(TEST_IDENTITIES.engineeringDepartment()));

        assertConflictChangesNothing(march2093());
    }

    @Test
    void conflictingPayrollPeriodFailsSafely() {
        extraRunIds.add(jdbc.queryForObject(
                "INSERT INTO payroll_runs (period_month, period_year, period_start, period_end, status) "
                        + "VALUES (2, 2093, '2093-02-01', '2093-02-28', 'PROCESSING') RETURNING id",
                Long.class));

        assertConflictChangesNothing(march2093());
    }

    @Test
    void lateFailureRollsBackEveryRowWithoutExposingPersistenceDetails() {
        DemoIdentities tooLongSecondIc = new DemoIdentities(
                TEST_IDENTITIES.adminEmail(), TEST_IDENTITIES.employeeEmail(), TEST_IDENTITIES.engineeringDepartment(),
                TEST_IDENTITIES.financeDepartment(), TEST_IDENTITIES.employeeOneCode(), TEST_IDENTITIES.employeeOneName(),
                TEST_IDENTITIES.employeeOneIc(), TEST_IDENTITIES.employeeTwoCode(), TEST_IDENTITIES.employeeTwoName(),
                "TESTDEMO-IC-0002-FAR-TOO-LONG");
        Map<String, Long> before = counts();

        assertThatThrownBy(() -> seeder(march2093(), tooLongSecondIc).seed(ADMIN_PASSWORD, EMPLOYEE_PASSWORD))
                .isInstanceOf(DemoDataConflictException.class)
                .hasMessage(DemoDataConflictException.MESSAGE)
                .hasNoCause();

        assertThat(counts()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM departments WHERE name IN (?, ?)", Long.class,
                TEST_IDENTITIES.engineeringDepartment(), TEST_IDENTITIES.financeDepartment())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM employees WHERE employee_code IN (?, ?)", Long.class,
                TEST_IDENTITIES.employeeOneCode(), TEST_IDENTITIES.employeeTwoCode())).isZero();
    }

    @Test
    void conflictMessageIsFixedAndFreeOfSensitiveDetail() {
        assertThat(DemoDataConflictException.MESSAGE)
                .doesNotContain("@")
                .doesNotContainIgnoringCase("sql")
                .doesNotContainIgnoringCase("constraint")
                .doesNotContainIgnoringCase("password")
                .doesNotContain("DEMO-");
    }

    // ---------------------------------------------------------------- schema

    @Test
    void flywayStillReportsExactlyThirteenAppliedMigrations() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success", Long.class)).isEqualTo(13);
    }

    // ---------------------------------------------------------------- helpers

    private void assertConflictChangesNothing(Clock clock) {
        Map<String, Long> before = counts();
        String snapshot = testRowSnapshot();
        Map<String, String> preExistingChecksums = preExistingChecksums();

        assertThatThrownBy(() -> seeder(clock).seed(ADMIN_PASSWORD, EMPLOYEE_PASSWORD))
                .isInstanceOf(DemoDataConflictException.class)
                .hasMessage(DemoDataConflictException.MESSAGE)
                .hasNoCause();

        assertThat(counts()).isEqualTo(before);
        assertThat(testRowSnapshot()).isEqualTo(snapshot);
        assertThat(preExistingChecksums()).isEqualTo(preExistingChecksums);
    }

    private void assertPlan(LocalDate date, int year, String approvedStart, String approvedEnd, String pendingStart, String pendingEnd) {
        DemoDataSeeder.LeavePlan plan = DemoDataSeeder.LeavePlan.forDate(date);
        assertThat(plan.year()).isEqualTo(year);
        assertThat(plan.approvedStart()).isEqualTo(approvedStart);
        assertThat(plan.approvedEnd()).isEqualTo(approvedEnd);
        assertThat(plan.pendingStart()).isEqualTo(pendingStart);
        assertThat(plan.pendingEnd()).isEqualTo(pendingEnd);
    }

    private DemoDataSeeder seeder(Clock clock) {
        return seeder(clock, TEST_IDENTITIES);
    }

    private DemoDataSeeder seeder(Clock clock, DemoIdentities identities) {
        return new DemoDataSeeder(entityManager, departmentRepository, employeeRepository, userRepository,
                salaryStructureRepository, leaveBalanceRepository, leaveRequestRepository, payrollRunRepository,
                payslipRepository, passwordEncoder, transactionManager, clock, identities);
    }

    private static Clock march2093() {
        return clockAt(LocalDateTime.of(2093, 3, 18, 10, 0));
    }

    private static Clock clockAt(LocalDateTime kualaLumpurDateTime) {
        return Clock.fixed(kualaLumpurDateTime.atZone(KL).toInstant(), KL);
    }

    private Map<String, Long> counts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : COUNTED_TABLES) {
            counts.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
        }
        return counts;
    }

    /** Checksums of every row that is not a TESTDEMO row, so any modification of unrelated data is detected. */
    private Map<String, String> preExistingChecksums() {
        String employeeFilter = "employee_id NOT IN (SELECT id FROM employees WHERE employee_code LIKE 'TESTDEMO%')";
        Map<String, String> checksums = new LinkedHashMap<>();
        checksums.put("users", checksum("users x WHERE email NOT LIKE 'testdemo.%'"));
        checksums.put("employees", checksum("employees x WHERE employee_code NOT LIKE 'TESTDEMO%'"));
        checksums.put("departments", checksum("departments x WHERE name NOT LIKE 'TESTDEMO%'"));
        checksums.put("leave_types", checksum("leave_types x"));
        checksums.put("salary_structures", checksum("salary_structures x WHERE " + employeeFilter));
        checksums.put("leave_balances", checksum("leave_balances x WHERE " + employeeFilter));
        checksums.put("leave_requests", checksum("leave_requests x WHERE " + employeeFilter));
        checksums.put("payslips", checksum("payslips x WHERE " + employeeFilter));
        checksums.put("attendance", checksum("attendance x"));
        return checksums;
    }

    private String checksum(String fromClause) {
        return jdbc.queryForObject("SELECT COALESCE(md5(string_agg(x::text, '|' ORDER BY x.id)), 'empty') FROM " + fromClause,
                String.class);
    }

    /** Full-row snapshot (including password hashes and timestamps) of every TESTDEMO row. */
    private String testRowSnapshot() {
        String employees = "(SELECT id FROM employees WHERE employee_code LIKE 'TESTDEMO%')";
        return String.join("#",
                checksum("departments x WHERE name LIKE 'TESTDEMO%'"),
                checksum("employees x WHERE employee_code LIKE 'TESTDEMO%'"),
                checksum("users x WHERE email LIKE 'testdemo.%'"),
                checksum("salary_structures x WHERE employee_id IN " + employees),
                checksum("leave_balances x WHERE employee_id IN " + employees),
                checksum("leave_requests x WHERE employee_id IN " + employees),
                checksum("payslips x WHERE employee_id IN " + employees),
                checksum("payroll_runs x WHERE id IN (SELECT payroll_run_id FROM payslips WHERE employee_id IN " + employees + ")"));
    }

    private long insertDepartment(String name) {
        return jdbc.queryForObject("INSERT INTO departments (name) VALUES (?) RETURNING id", Long.class, name);
    }

    private long insertEmployee(String code, String ic, long departmentId) {
        return jdbc.queryForObject(
                "INSERT INTO employees (employee_code, full_name, ic_number, department_id, job_title, employment_status, hire_date) "
                        + "VALUES (?, 'TESTDEMO-X Unrelated', ?, ?, 'Unrelated', 'ACTIVE', '2090-01-02') RETURNING id",
                Long.class, code, ic, departmentId);
    }

    private List<Long> ids(String sql, Object... args) {
        return new ArrayList<>(jdbc.queryForList(sql, Long.class, args));
    }

    private void deleteByIds(String sqlTemplate, List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.update(String.format(sqlTemplate, in(ids)), ids.toArray());
    }

    private static String in(List<Long> ids) {
        return String.join(", ", java.util.Collections.nCopies(ids.size(), "?"));
    }

    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put((String) keyValues[i], keyValues[i + 1]);
        }
        return row;
    }
}
