package com.hrpayroll.backend.support;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Raw-SQL fixtures for Phase 8A-2 payroll schema/entity tests, kept in a
 * separate "Phase8 Test Department"/"EMP-PHASE8-" namespace and a reserved
 * {@code period_year} (2099) so these suites never collide with any other
 * phase's fixtures or with real manual/demo payroll runs.
 */
public final class PayrollTestFixtures {

    public static final String DEPARTMENT_NAME = "Phase8 Test Department";
    public static final String EMPLOYEE_CODE_PREFIX = "EMP-PHASE8-";

    /** Reserved for automated-test payroll runs only -- never used for manual/demo runs. */
    public static final int TEST_PERIOD_YEAR = 2099;

    private PayrollTestFixtures() {
    }

    public static long installDepartment(JdbcTemplate jdbc) {
        return jdbc.queryForObject("INSERT INTO departments (name) VALUES (?) RETURNING id", Long.class, DEPARTMENT_NAME);
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

    /** Attempts an insert with an explicit period_year -- used to prove the V13 year-range CHECK. */
    public static void insertPayrollRunWithPeriodYear(JdbcTemplate jdbc, int periodMonth, int periodYear) {
        LocalDate periodStart = LocalDate.of(2026, periodMonth, 1); // period_start/end need not be valid for this check
        LocalDate periodEnd = periodStart.withDayOfMonth(periodStart.lengthOfMonth());
        jdbc.update(
                "INSERT INTO payroll_runs (period_month, period_year, period_start, period_end, status) "
                        + "VALUES (?, ?, ?, ?, 'PROCESSING')",
                periodMonth,
                periodYear,
                periodStart,
                periodEnd);
    }

    /** Attempts an insert with explicit rate snapshots -- used to prove the V13 rate-range/sum CHECKs. */
    public static void insertPayrollRunWithRates(
            JdbcTemplate jdbc,
            int periodMonth,
            int periodYear,
            BigDecimal epfRate,
            BigDecimal socsoRate,
            BigDecimal eisRate,
            BigDecimal incomeTaxRate) {
        LocalDate periodStart = LocalDate.of(periodYear, periodMonth, 1);
        LocalDate periodEnd = periodStart.withDayOfMonth(periodStart.lengthOfMonth());
        jdbc.update(
                "INSERT INTO payroll_runs "
                        + "(period_month, period_year, period_start, period_end, status, "
                        + "epf_rate_snapshot, socso_rate_snapshot, eis_rate_snapshot, income_tax_rate_snapshot) "
                        + "VALUES (?, ?, ?, ?, 'PROCESSING', ?, ?, ?, ?)",
                periodMonth,
                periodYear,
                periodStart,
                periodEnd,
                epfRate,
                socsoRate,
                eisRate,
                incomeTaxRate);
    }

    /** Safe to call even if nothing exists. Only ever touches the reserved TEST_PERIOD_YEAR and Phase8 fixtures. */
    public static void cleanup(JdbcTemplate jdbc) {
        jdbc.update(
                "DELETE FROM payslips WHERE employee_id IN (SELECT id FROM employees WHERE employee_code LIKE ?)",
                EMPLOYEE_CODE_PREFIX + "%");
        jdbc.update("DELETE FROM payroll_runs WHERE period_year = ?", TEST_PERIOD_YEAR);
        jdbc.update("DELETE FROM employees WHERE employee_code LIKE ?", EMPLOYEE_CODE_PREFIX + "%");
        jdbc.update("DELETE FROM departments WHERE name = ?", DEPARTMENT_NAME);
    }
}
