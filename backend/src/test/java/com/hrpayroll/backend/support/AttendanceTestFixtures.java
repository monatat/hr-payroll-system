package com.hrpayroll.backend.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Raw-SQL fixtures for Phase 9 attendance tests, kept in a separate
 * "Phase9"/"EMP-PHASE9-" namespace from earlier phases' fixtures so the
 * suites never collide.
 */
public final class AttendanceTestFixtures {

    public static final String DEPARTMENT_NAME = "Phase9 Test Department";

    public static final String EMPLOYEE_CODE_PREFIX = "EMP-PHASE9-";

    private AttendanceTestFixtures() {
    }

    public static long installDepartment(JdbcTemplate jdbc) {
        return jdbc.queryForObject("INSERT INTO departments (name) VALUES (?) RETURNING id", Long.class, DEPARTMENT_NAME);
    }

    public static long insertEmployee(JdbcTemplate jdbc, long departmentId, String codeSuffix, String icNumber) {
        return jdbc.queryForObject(
                "INSERT INTO employees "
                        + "(employee_code, full_name, ic_number, department_id, job_title, employment_status, "
                        + "hire_date, bank_name, bank_account_no, phone_number) "
                        + "VALUES (?, 'Phase9 Test Employee', ?, ?, 'Engineer', 'ACTIVE', CURRENT_DATE, "
                        + "'Test Bank', '1112223333', NULL) RETURNING id",
                Long.class,
                EMPLOYEE_CODE_PREFIX + codeSuffix,
                icNumber,
                departmentId);
    }

    /** Safe to call even if nothing exists. */
    public static void cleanup(JdbcTemplate jdbc) {
        jdbc.update(
                "DELETE FROM attendance WHERE employee_id IN "
                        + "(SELECT id FROM employees WHERE employee_code LIKE ?)",
                EMPLOYEE_CODE_PREFIX + "%");
        jdbc.update("DELETE FROM employees WHERE employee_code LIKE ?", EMPLOYEE_CODE_PREFIX + "%");
        jdbc.update("DELETE FROM departments WHERE name = ?", DEPARTMENT_NAME);
    }
}
