package com.hrpayroll.backend.support;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Raw-SQL fixtures for Phase 10A report-query tests, kept in a separate
 * "Phase10"/"EMP-PHASE10-"/"REPORT-PHASE10-" namespace so the suites never collide with any other
 * phase's fixtures. Payroll runs and payslips are inserted directly, bypassing
 * PayrollRunTransactionService entirely -- this is the only way to construct the deliberately
 * anomalous PROCESSING/FAILED-run payslip fixtures the report-query tests need to prove the
 * explicit status filter (the real service design never produces such rows).
 */
public final class ReportTestFixtures {

    public static final String DEPARTMENT_NAME_PREFIX = "REPORT-PHASE10-DEPT-";
    public static final String EMPLOYEE_CODE_PREFIX = "EMP-PHASE10-";

    private ReportTestFixtures() {
    }

    public static long installDepartment(JdbcTemplate jdbc, String suffix) {
        return jdbc.queryForObject(
                "INSERT INTO departments (name) VALUES (?) RETURNING id", Long.class, DEPARTMENT_NAME_PREFIX + suffix);
    }

    public static long insertEmployee(JdbcTemplate jdbc, long departmentId, String codeSuffix, String icNumber, String employmentStatus) {
        return jdbc.queryForObject(
                "INSERT INTO employees "
                        + "(employee_code, full_name, ic_number, department_id, job_title, employment_status, "
                        + "hire_date, bank_name, bank_account_no, phone_number) "
                        + "VALUES (?, 'Phase10 Test Employee', ?, ?, 'Engineer', ?, '1999-01-01', "
                        + "'Test Bank', '1112223333', NULL) RETURNING id",
                Long.class,
                EMPLOYEE_CODE_PREFIX + codeSuffix,
                icNumber,
                departmentId,
                employmentStatus);
    }

    /** period_start/period_end are derived from month/year; every V13 rate-snapshot column and version use their DB defaults. */
    public static long insertPayrollRun(JdbcTemplate jdbc, int month, int year, String status) {
        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
        return jdbc.queryForObject(
                "INSERT INTO payroll_runs (period_month, period_year, period_start, period_end, status) "
                        + "VALUES (?, ?, ?, ?, ?) RETURNING id",
                Long.class,
                month,
                year,
                start,
                end,
                status);
    }

    /** basePay/netPay are deliberately set equal to grossPay so a test can prove the report never substitutes the wrong field. */
    public static long insertPayslip(
            JdbcTemplate jdbc, long payrollRunId, long employeeId, String employeeCodeSnapshot, BigDecimal grossPay) {
        return jdbc.queryForObject(
                "INSERT INTO payslips "
                        + "(payroll_run_id, employee_id, employee_code_snapshot, employee_name_snapshot, "
                        + "department_name_snapshot, job_title_snapshot, base_pay, housing_allowance, "
                        + "transport_allowance, gross_pay, epf_deduction, socso_deduction, eis_deduction, "
                        + "income_tax, total_deductions, net_pay, pdf_path, pdf_status) "
                        + "VALUES (?, ?, ?, 'Phase10 Test Employee', 'Phase10 Test Department', 'Engineer', "
                        + "?, 0, 0, ?, 0, 0, 0, 0, 0, ?, NULL, 'PENDING') RETURNING id",
                Long.class,
                payrollRunId,
                employeeId,
                employeeCodeSnapshot,
                grossPay,
                grossPay,
                grossPay);
    }

    /**
     * basePay/netPay deliberately differ from grossPay, so a test can prove the report reads
     * grossPay specifically and never accidentally substitutes basePay or netPay.
     */
    public static long insertPayslipWithDistinctBasePayAndNetPay(
            JdbcTemplate jdbc,
            long payrollRunId,
            long employeeId,
            String employeeCodeSnapshot,
            BigDecimal basePay,
            BigDecimal grossPay,
            BigDecimal netPay) {
        return jdbc.queryForObject(
                "INSERT INTO payslips "
                        + "(payroll_run_id, employee_id, employee_code_snapshot, employee_name_snapshot, "
                        + "department_name_snapshot, job_title_snapshot, base_pay, housing_allowance, "
                        + "transport_allowance, gross_pay, epf_deduction, socso_deduction, eis_deduction, "
                        + "income_tax, total_deductions, net_pay, pdf_path, pdf_status) "
                        + "VALUES (?, ?, ?, 'Phase10 Test Employee', 'Phase10 Test Department', 'Engineer', "
                        + "?, 0, 0, ?, 0, 0, 0, 0, 0, ?, NULL, 'PENDING') RETURNING id",
                Long.class,
                payrollRunId,
                employeeId,
                employeeCodeSnapshot,
                basePay,
                grossPay,
                netPay);
    }

    /** Safe to call even if nothing exists. Never touches audit_logs (append-only, retained). */
    public static void cleanup(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM payslips WHERE employee_code_snapshot LIKE ?", EMPLOYEE_CODE_PREFIX + "%");
        jdbc.update(
                "DELETE FROM employees WHERE employee_code LIKE ?", EMPLOYEE_CODE_PREFIX + "%");
        jdbc.update("DELETE FROM departments WHERE name LIKE ?", DEPARTMENT_NAME_PREFIX + "%");
    }

    /** Payroll run ids are never derivable by a LIKE filter -- callers must track and pass exact ids. */
    public static void cleanupPayrollRuns(JdbcTemplate jdbc, Iterable<Long> payrollRunIds) {
        for (Long id : payrollRunIds) {
            jdbc.update("DELETE FROM payroll_runs WHERE id = ?", id);
        }
    }
}
