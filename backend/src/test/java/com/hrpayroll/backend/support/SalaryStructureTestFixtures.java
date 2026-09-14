package com.hrpayroll.backend.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Raw-SQL fixtures for Phase 6 salary structure tests, kept in a separate
 * "phase6.*"/"EMP-PHASE6-*" namespace from earlier phases' fixtures so the
 * suites never collide.
 */
public final class SalaryStructureTestFixtures {

    public static final String DEPARTMENT_NAME = "Phase6 Test Department";

    public static final String ADMIN_EMAIL = "phase6.admin@test.local";
    public static final String EMPLOYEE_EMAIL = "phase6.employee@test.local";
    public static final String OTHER_EMPLOYEE_EMAIL = "phase6.other-employee@test.local";

    public static final String EMPLOYEE_CODE_PREFIX = "EMP-PHASE6-";

    private SalaryStructureTestFixtures() {
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
                passwordEncoder.encode("Phase6AdminPass123!"));
    }

    public static long insertEmployee(JdbcTemplate jdbc, long departmentId, String codeSuffix, String icNumber) {
        return jdbc.queryForObject(
                "INSERT INTO employees "
                        + "(employee_code, full_name, ic_number, department_id, job_title, employment_status, "
                        + "hire_date, bank_name, bank_account_no, phone_number) "
                        + "VALUES (?, 'Phase6 Test Employee', ?, ?, 'Engineer', 'ACTIVE', CURRENT_DATE, "
                        + "'Test Bank', '1112223333', NULL) RETURNING id",
                Long.class,
                EMPLOYEE_CODE_PREFIX + codeSuffix,
                icNumber,
                departmentId);
    }

    /** Inserts an employee plus a linked EMPLOYEE user and returns [employeeId, userId]. */
    public static long[] installEmployeeWithUser(
            JdbcTemplate jdbc, PasswordEncoder passwordEncoder, long departmentId, String codeSuffix, String icNumber) {
        long employeeId = insertEmployee(jdbc, departmentId, codeSuffix, icNumber);
        long userId = jdbc.queryForObject(
                "INSERT INTO users (email, password_hash, role, account_status, employee_id) "
                        + "VALUES (?, ?, 'EMPLOYEE', 'ACTIVE', ?) RETURNING id",
                Long.class,
                EMPLOYEE_EMAIL,
                passwordEncoder.encode("Phase6EmployeePass123!"),
                employeeId);
        return new long[] {employeeId, userId};
    }

    public static long[] installOtherEmployeeWithUser(
            JdbcTemplate jdbc, PasswordEncoder passwordEncoder, long departmentId, String codeSuffix, String icNumber) {
        long employeeId = insertEmployee(jdbc, departmentId, codeSuffix, icNumber);
        long userId = jdbc.queryForObject(
                "INSERT INTO users (email, password_hash, role, account_status, employee_id) "
                        + "VALUES (?, ?, 'EMPLOYEE', 'ACTIVE', ?) RETURNING id",
                Long.class,
                OTHER_EMPLOYEE_EMAIL,
                passwordEncoder.encode("Phase6OtherEmployeePass123!"),
                employeeId);
        return new long[] {employeeId, userId};
    }

    /** Safe to call even if nothing exists. */
    public static void cleanup(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM users WHERE email IN (?, ?, ?)", ADMIN_EMAIL, EMPLOYEE_EMAIL, OTHER_EMPLOYEE_EMAIL);
        jdbc.update(
                "DELETE FROM salary_structures WHERE employee_id IN "
                        + "(SELECT id FROM employees WHERE employee_code LIKE ?)",
                EMPLOYEE_CODE_PREFIX + "%");
        jdbc.update("DELETE FROM employees WHERE employee_code LIKE ?", EMPLOYEE_CODE_PREFIX + "%");
        jdbc.update("DELETE FROM departments WHERE name = ?", DEPARTMENT_NAME);
    }
}
