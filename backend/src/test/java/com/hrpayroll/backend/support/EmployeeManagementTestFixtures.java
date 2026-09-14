package com.hrpayroll.backend.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Raw-SQL fixtures for Phase 5 department/employee tests, deliberately kept
 * in a separate namespace ("phase5.*" emails, "EMP-PHASE5-*" codes,
 * "Phase5 Test Department *" names) from Phase 4's SecurityTestFixtures so
 * the two suites never collide.
 */
public final class EmployeeManagementTestFixtures {

    public static final String DEPARTMENT_A_NAME = "Phase5 Test Department A";
    public static final String DEPARTMENT_B_NAME = "Phase5 Test Department B";

    public static final String ADMIN_EMAIL = "phase5.admin@test.local";
    public static final String ADMIN_PASSWORD = "Phase5AdminPass123!";

    public static final String EMPLOYEE_EMAIL = "phase5.employee@test.local";
    public static final String EMPLOYEE_PASSWORD = "Phase5EmployeePass123!";

    public static final String EMPLOYEE_CODE_PREFIX = "EMP-PHASE5-";

    private EmployeeManagementTestFixtures() {
    }

    public static long installDepartmentA(JdbcTemplate jdbc) {
        return jdbc.queryForObject("INSERT INTO departments (name) VALUES (?) RETURNING id", Long.class, DEPARTMENT_A_NAME);
    }

    public static long installDepartmentB(JdbcTemplate jdbc) {
        return jdbc.queryForObject("INSERT INTO departments (name) VALUES (?) RETURNING id", Long.class, DEPARTMENT_B_NAME);
    }

    public static long installAdminUser(JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
        return jdbc.queryForObject(
                "INSERT INTO users (email, password_hash, role, account_status, employee_id) "
                        + "VALUES (?, ?, 'HR_ADMIN', 'ACTIVE', NULL) RETURNING id",
                Long.class,
                ADMIN_EMAIL,
                passwordEncoder.encode(ADMIN_PASSWORD));
    }

    /** Inserts an employee row (no linked user) and returns its id. */
    public static long insertEmployee(
            JdbcTemplate jdbc, long departmentId, String codeSuffix, String fullName, String icNumber) {
        return jdbc.queryForObject(
                "INSERT INTO employees "
                        + "(employee_code, full_name, ic_number, department_id, job_title, employment_status, "
                        + "hire_date, bank_name, bank_account_no, phone_number) "
                        + "VALUES (?, ?, ?, ?, 'Tester', 'ACTIVE', CURRENT_DATE, 'Test Bank', '1112223333', NULL) "
                        + "RETURNING id",
                Long.class,
                EMPLOYEE_CODE_PREFIX + codeSuffix,
                fullName,
                icNumber,
                departmentId);
    }

    /** Inserts an employee plus a linked EMPLOYEE user and returns [employeeId, userId]. */
    public static long[] installEmployeeWithUser(
            JdbcTemplate jdbc, PasswordEncoder passwordEncoder, long departmentId, String codeSuffix, String icNumber) {
        long employeeId = insertEmployee(jdbc, departmentId, codeSuffix, "Phase5 Self Service Employee", icNumber);
        long userId = jdbc.queryForObject(
                "INSERT INTO users (email, password_hash, role, account_status, employee_id) "
                        + "VALUES (?, ?, 'EMPLOYEE', 'ACTIVE', ?) RETURNING id",
                Long.class,
                EMPLOYEE_EMAIL,
                passwordEncoder.encode(EMPLOYEE_PASSWORD),
                employeeId);
        return new long[] {employeeId, userId};
    }

    /** Deletes every row in the "phase5.*"/"EMP-PHASE5-*" namespace. Safe to call even if nothing exists. */
    public static void cleanup(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM users WHERE email IN (?, ?)", ADMIN_EMAIL, EMPLOYEE_EMAIL);
        jdbc.update("DELETE FROM employees WHERE employee_code LIKE ?", EMPLOYEE_CODE_PREFIX + "%");
        jdbc.update("DELETE FROM departments WHERE name IN (?, ?)", DEPARTMENT_A_NAME, DEPARTMENT_B_NAME);
    }
}
