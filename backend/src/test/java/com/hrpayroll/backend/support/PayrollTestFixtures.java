package com.hrpayroll.backend.support;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Raw-SQL fixtures for Phase 8A-2/8B-1 payroll tests, kept in a separate
 * "Phase8 Test Department"/"EMP-PHASE8-" namespace and two reserved
 * {@code period_year} values (2099 for schema-only tests, 2000 for
 * candidate/calculation tests -- chosen because, as of this phase, the only
 * employees in the shared database are hired in 2025/2026, so no unrelated
 * ACTIVE employee can ever qualify as a candidate for a 2000 period) so
 * these suites never collide with any other phase's fixtures or with real
 * manual/demo payroll runs.
 */
public final class PayrollTestFixtures {

    public static final String DEPARTMENT_NAME = "Phase8 Test Department";
    public static final String EMPLOYEE_CODE_PREFIX = "EMP-PHASE8-";
    public static final String ADMIN_EMAIL = "phase8.admin@test.local";

    /** Reserved for Phase 8A-2 schema-only payroll_runs tests -- never used for manual/demo runs. */
    public static final int TEST_PERIOD_YEAR = 2099;

    /**
     * Reserved band for Phase 8B-1 candidate/calculation tests. Verified read-only against the
     * shared database before use: no ACTIVE employee has hire_date on/before any period_start in
     * this range (the earliest existing employee, P7DEMO01, was hired 2025-01-06) -- so a candidate
     * query for any period in this band can only ever find employees this fixture itself inserts.
     * Several years are reserved (not just one) so independent test methods needing their own
     * period -- beyond the 12 months available in a single year -- never collide.
     */
    public static final int CANDIDATE_TEST_PERIOD_YEAR = 2000;

    public static final int CANDIDATE_TEST_PERIOD_YEAR_MIN = 2000;
    public static final int CANDIDATE_TEST_PERIOD_YEAR_MAX = 2005;

    /**
     * Reserved for Phase 8B-2A controller/query tests. Same isolation guarantee as
     * CANDIDATE_TEST_PERIOD_YEAR -- no existing employee qualifies as a candidate for this year --
     * kept as its own constant (rather than reusing 2000-2005) purely so this sub-phase's tests are
     * unambiguous to read, not because sharing the earlier band would be unsafe.
     */
    public static final int CONTROLLER_TEST_PERIOD_YEAR = 2006;

    /**
     * Reserved band for Phase 8C-2B1 PDF-orchestration tests. Same isolation guarantee as
     * CANDIDATE_TEST_PERIOD_YEAR -- no existing employee qualifies as a candidate for any of these
     * years -- kept as its own fresh band (never used by any earlier phase's tests) so PDF
     * generation/concurrency/isolation tests never collide with the payroll-run-only test classes
     * that already reserve individual years within 2000-2012.
     */
    public static final int PDF_TEST_PERIOD_YEAR_MIN = 2013;

    public static final int PDF_TEST_PERIOD_YEAR_MAX = 2020;

    private PayrollTestFixtures() {
    }

    public static long installDepartment(JdbcTemplate jdbc) {
        return jdbc.queryForObject("INSERT INTO departments (name) VALUES (?) RETURNING id", Long.class, DEPARTMENT_NAME);
    }

    public static long installAdminUser(JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
        return jdbc.queryForObject(
                "INSERT INTO users (email, password_hash, role, account_status, employee_id) "
                        + "VALUES (?, ?, 'HR_ADMIN', 'ACTIVE', NULL) RETURNING id",
                Long.class,
                ADMIN_EMAIL,
                passwordEncoder.encode("Phase8AdminPass123!"));
    }

    public static long insertEmployee(JdbcTemplate jdbc, long departmentId, String codeSuffix, String icNumber) {
        return jdbc.queryForObject(
                "INSERT INTO employees "
                        + "(employee_code, full_name, ic_number, department_id, job_title, employment_status, "
                        + "hire_date, bank_name, bank_account_no, phone_number) "
                        + "VALUES (?, 'Phase8 Test Employee', ?, ?, 'Engineer', 'ACTIVE', CURRENT_DATE, "
                        + "'Test Bank', '1112223333', NULL) RETURNING id",
                Long.class,
                EMPLOYEE_CODE_PREFIX + codeSuffix,
                icNumber,
                departmentId);
    }

    /** Inserts an employee with an explicit hire date and employment status -- for candidate-eligibility tests. */
    public static long insertEmployee(
            JdbcTemplate jdbc,
            long departmentId,
            String codeSuffix,
            String icNumber,
            LocalDate hireDate,
            String employmentStatus) {
        return jdbc.queryForObject(
                "INSERT INTO employees "
                        + "(employee_code, full_name, ic_number, department_id, job_title, employment_status, "
                        + "hire_date, bank_name, bank_account_no, phone_number) "
                        + "VALUES (?, 'Phase8 Test Employee', ?, ?, 'Engineer', ?, ?, "
                        + "'Test Bank', '1112223333', NULL) RETURNING id",
                Long.class,
                EMPLOYEE_CODE_PREFIX + codeSuffix,
                icNumber,
                departmentId,
                employmentStatus,
                hireDate);
    }

    /**
     * Raw-SQL payslip insert, bypassing PayrollRunTransactionService entirely -- needed so a
     * controller test can verify list/pagination/ordering/serialization behavior against a known
     * payslip without needing genuine candidate/coverage data for every scenario. Uses the
     * "Phase8 Test..." snapshot values directly and always PdfStatus PENDING with a null path,
     * matching what the real workflow always produces in this sub-phase.
     */
    public static long insertPayslip(
            JdbcTemplate jdbc,
            long payrollRunId,
            long employeeId,
            String employeeCodeSnapshot,
            BigDecimal basePay,
            BigDecimal housingAllowance,
            BigDecimal transportAllowance,
            BigDecimal grossPay,
            BigDecimal epfDeduction,
            BigDecimal socsoDeduction,
            BigDecimal eisDeduction,
            BigDecimal incomeTax,
            BigDecimal totalDeductions,
            BigDecimal netPay) {
        return jdbc.queryForObject(
                "INSERT INTO payslips "
                        + "(payroll_run_id, employee_id, employee_code_snapshot, employee_name_snapshot, "
                        + "department_name_snapshot, job_title_snapshot, base_pay, housing_allowance, "
                        + "transport_allowance, gross_pay, epf_deduction, socso_deduction, eis_deduction, "
                        + "income_tax, total_deductions, net_pay, pdf_path, pdf_status) "
                        + "VALUES (?, ?, ?, 'Phase8 Test Employee', ?, 'Engineer', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, 'PENDING') "
                        + "RETURNING id",
                Long.class,
                payrollRunId,
                employeeId,
                employeeCodeSnapshot,
                DEPARTMENT_NAME,
                basePay,
                housingAllowance,
                transportAllowance,
                grossPay,
                epfDeduction,
                socsoDeduction,
                eisDeduction,
                incomeTax,
                totalDeductions,
                netPay);
    }

    /**
     * Raw-SQL salary structure insert, bypassing SalaryStructureService entirely -- needed so a test
     * can deliberately create overlapping/ambiguous coverage that the service's own overlap check
     * would otherwise reject.
     */
    public static long insertSalaryStructure(
            JdbcTemplate jdbc,
            long employeeId,
            BigDecimal basePay,
            BigDecimal housingAllowance,
            BigDecimal transportAllowance,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {
        return jdbc.queryForObject(
                "INSERT INTO salary_structures "
                        + "(employee_id, base_pay, housing_allowance, transport_allowance, effective_from, effective_to) "
                        + "VALUES (?, ?, ?, ?, ?, ?) RETURNING id",
                Long.class,
                employeeId,
                basePay,
                housingAllowance,
                transportAllowance,
                effectiveFrom,
                effectiveTo);
    }

    /** Inserts a payroll_runs row using only the columns that existed before V13 -- proves the V13 defaults apply. */
    public static long insertPayrollRunWithoutV13Columns(JdbcTemplate jdbc, int periodMonth, int periodYear) {
        LocalDate periodStart = LocalDate.of(periodYear, periodMonth, 1);
        LocalDate periodEnd = periodStart.withDayOfMonth(periodStart.lengthOfMonth());
        return jdbc.queryForObject(
                "INSERT INTO payroll_runs (period_month, period_year, period_start, period_end, status) "
                        + "VALUES (?, ?, ?, ?, 'PROCESSING') RETURNING id",
                Long.class,
                periodMonth,
                periodYear,
                periodStart,
                periodEnd);
    }

    /**
     * Inserts a payroll_runs row with an explicit status and created_at/updated_at -- used to prove
     * the createdAt-DESC/id-DESC deterministic tiebreaker when two rows share an identical
     * created_at. Bypasses PayrollRunService entirely, so the returned id must be passed to
     * {@link #cleanup(JdbcTemplate, Collection)}.
     */
    public static long insertPayrollRunWithCreatedAt(
            JdbcTemplate jdbc, int periodMonth, int periodYear, String status, java.time.LocalDateTime createdAt) {
        LocalDate periodStart = LocalDate.of(periodYear, periodMonth, 1);
        LocalDate periodEnd = periodStart.withDayOfMonth(periodStart.lengthOfMonth());
        return jdbc.queryForObject(
                "INSERT INTO payroll_runs (period_month, period_year, period_start, period_end, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id",
                Long.class,
                periodMonth,
                periodYear,
                periodStart,
                periodEnd,
                status,
                createdAt,
                createdAt);
    }

    /**
     * Attempts an insert with an explicit period_year -- used to prove the V13 year-range CHECK.
     * Returns the new row's id on success (pass it to {@link #cleanup(JdbcTemplate, Collection)});
     * every caller of this method expects the insert to be rejected, so there is normally no id to
     * track, but the return value keeps this fixture correct for any future caller that does not.
     */
    public static long insertPayrollRunWithPeriodYear(JdbcTemplate jdbc, int periodMonth, int periodYear) {
        LocalDate periodStart = LocalDate.of(2026, periodMonth, 1); // period_start/end need not be valid for this check
        LocalDate periodEnd = periodStart.withDayOfMonth(periodStart.lengthOfMonth());
        return jdbc.queryForObject(
                "INSERT INTO payroll_runs (period_month, period_year, period_start, period_end, status) "
                        + "VALUES (?, ?, ?, ?, 'PROCESSING') RETURNING id",
                Long.class,
                periodMonth,
                periodYear,
                periodStart,
                periodEnd);
    }

    /**
     * Attempts an insert with explicit rate snapshots -- used to prove the V13 rate-range/sum
     * CHECKs. Returns the new row's id on success, so a caller whose insert is expected to succeed
     * can pass it to {@link #cleanup(JdbcTemplate, Collection)} for precise, marker-free cleanup.
     */
    public static long insertPayrollRunWithRates(
            JdbcTemplate jdbc,
            int periodMonth,
            int periodYear,
            BigDecimal epfRate,
            BigDecimal socsoRate,
            BigDecimal eisRate,
            BigDecimal incomeTaxRate) {
        LocalDate periodStart = LocalDate.of(periodYear, periodMonth, 1);
        LocalDate periodEnd = periodStart.withDayOfMonth(periodStart.lengthOfMonth());
        return jdbc.queryForObject(
                "INSERT INTO payroll_runs "
                        + "(period_month, period_year, period_start, period_end, status, "
                        + "epf_rate_snapshot, socso_rate_snapshot, eis_rate_snapshot, income_tax_rate_snapshot) "
                        + "VALUES (?, ?, ?, ?, 'PROCESSING', ?, ?, ?, ?) RETURNING id",
                Long.class,
                periodMonth,
                periodYear,
                periodStart,
                periodEnd,
                epfRate,
                socsoRate,
                eisRate,
                incomeTaxRate);
    }

    /** Equivalent to {@code cleanup(jdbc, List.of())} -- for tests that never directly insert a payroll_runs row without going through the audited service. */
    public static void cleanup(JdbcTemplate jdbc) {
        cleanup(jdbc, List.of());
    }

    /**
     * Safe to call even if nothing exists. Deletes only rows identified by a test-owned marker or
     * an explicitly tracked id -- deliberately never by period_year alone, since the reserved test
     * years (2000-2005, 2099) are still ordinary, potentially real, business years and "reserved
     * for tests" is not itself a safety boundary. A payroll run is targeted only if either:
     * <ul>
     *   <li>it has a PAYROLL_RUN_STARTED audit row whose actor is the fixture admin user (every
     *       test that goes through the real PayrollRunService satisfies this), or</li>
     *   <li>its id is explicitly passed in {@code extraPayrollRunIds} (for tests that insert a
     *       payroll_runs row directly, bypassing the service and therefore leaving no audit trail
     *       -- e.g. schema/constraint/repository-mapping tests).</li>
     * </ul>
     * Deletion order: audit rows for the identified runs -> payslips (by fixture employee code, or
     * by the identified run ids) -> the identified payroll runs -> salary structures/users/
     * employees/departments (by fixture employee-code prefix / fixture admin email / fixture
     * department name -- all test-owned markers, never a year or date range).
     */
    public static void cleanup(JdbcTemplate jdbc, Collection<Long> extraPayrollRunIds) {
        List<Long> adminAuditedRunIds = jdbc.query(
                "SELECT DISTINCT al.entity_id FROM audit_logs al JOIN users u ON u.id = al.actor_id "
                        + "WHERE al.entity_name = 'PAYROLL_RUN' AND al.action = 'PAYROLL_RUN_STARTED' AND u.email = ?",
                (rs, rowNum) -> rs.getLong(1),
                ADMIN_EMAIL);

        Set<Long> runIds = new LinkedHashSet<>(adminAuditedRunIds);
        runIds.addAll(extraPayrollRunIds);

        if (!runIds.isEmpty()) {
            Object[] idArray = runIds.toArray();
            String inClause = placeholders(idArray.length);
            jdbc.update("DELETE FROM audit_logs WHERE entity_name = 'PAYROLL_RUN' AND entity_id IN (" + inClause + ")", idArray);
            jdbc.update("DELETE FROM payslips WHERE payroll_run_id IN (" + inClause + ")", idArray);
        }

        jdbc.update(
                "DELETE FROM payslips WHERE employee_id IN (SELECT id FROM employees WHERE employee_code LIKE ?)",
                EMPLOYEE_CODE_PREFIX + "%");

        if (!runIds.isEmpty()) {
            Object[] idArray = runIds.toArray();
            jdbc.update("DELETE FROM payroll_runs WHERE id IN (" + placeholders(idArray.length) + ")", idArray);
        }

        jdbc.update(
                "DELETE FROM salary_structures WHERE employee_id IN (SELECT id FROM employees WHERE employee_code LIKE ?)",
                EMPLOYEE_CODE_PREFIX + "%");
        jdbc.update("DELETE FROM users WHERE email = ?", ADMIN_EMAIL);
        jdbc.update("DELETE FROM employees WHERE employee_code LIKE ?", EMPLOYEE_CODE_PREFIX + "%");
        jdbc.update("DELETE FROM departments WHERE name = ?", DEPARTMENT_NAME);
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }
}
