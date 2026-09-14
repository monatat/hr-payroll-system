package com.hrpayroll.backend.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Raw-SQL test fixtures for security integration tests. Uses JdbcTemplate
 * (not the Phase 4 JPA repositories) deliberately, so fixture setup does not
 * depend on the very code under test, and inserts/deletes rows for a fixed
 * set of "phase4.*" test emails / codes so tests are safely repeatable
 * against the shared local Postgres instance.
 */
public final class SecurityTestFixtures {

    public static final String DEPARTMENT_NAME = "Phase4 Test Department";
    public static final String EMPLOYEE_CODE = "EMP-PHASE4-TEST";
    public static final String EMPLOYEE_IC_NUMBER = "990101-01-9999";
    public static final String EMPLOYEE_BANK_NAME = "Test Bank Berhad";
    public static final String EMPLOYEE_BANK_ACCOUNT_NO = "1234567890";

    public static final String ADMIN_EMAIL = "phase4.admin@test.local";
    public static final String ADMIN_PASSWORD = "AdminPass123!";

    public static final String EMPLOYEE_EMAIL = "phase4.employee@test.local";
    public static final String EMPLOYEE_PASSWORD = "EmployeePass123!";

    public static final String DISABLED_EMAIL = "phase4.disabled@test.local";
    public static final String DISABLED_PASSWORD = "DisabledPass123!";

    private SecurityTestFixtures() {
    }

    /** Inserts a department + employee + admin/employee/disabled users. Returns the employee id. */
    public static long install(JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
        cleanup(jdbc);

        Long departmentId = jdbc.queryForObject(
                "INSERT INTO departments (name) VALUES (?) RETURNING id", Long.class, DEPARTMENT_NAME);

        Long employeeId = jdbc.queryForObject(
                "INSERT INTO employees "
                        + "(employee_code, full_name, ic_number, department_id, job_title, employment_status, "
                        + "hire_date, bank_name, bank_account_no) "
                        + "VALUES (?, ?, ?, ?, ?, 'ACTIVE', CURRENT_DATE, ?, ?) RETURNING id",
                Long.class,
                EMPLOYEE_CODE,
                "Phase4 Test Employee",
                EMPLOYEE_IC_NUMBER,
                departmentId,
                "Tester",
                EMPLOYEE_BANK_NAME,
                EMPLOYEE_BANK_ACCOUNT_NO);

        jdbc.update(
                "INSERT INTO users (email, password_hash, role, account_status, employee_id) VALUES (?, ?, 'HR_ADMIN', 'ACTIVE', NULL)",
                ADMIN_EMAIL,
                passwordEncoder.encode(ADMIN_PASSWORD));

        jdbc.update(
                "INSERT INTO users (email, password_hash, role, account_status, employee_id) VALUES (?, ?, 'EMPLOYEE', 'ACTIVE', ?)",
                EMPLOYEE_EMAIL,
                passwordEncoder.encode(EMPLOYEE_PASSWORD),
                employeeId);

        jdbc.update(
                "INSERT INTO users (email, password_hash, role, account_status, employee_id) VALUES (?, ?, 'HR_ADMIN', 'DISABLED', NULL)",
                DISABLED_EMAIL,
                passwordEncoder.encode(DISABLED_PASSWORD));

        return employeeId;
    }

    /** Deletes fixture rows in FK-safe order. Safe to call even if nothing exists yet. */
    public static void cleanup(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM users WHERE email IN (?, ?, ?)", ADMIN_EMAIL, EMPLOYEE_EMAIL, DISABLED_EMAIL);
        jdbc.update("DELETE FROM employees WHERE employee_code = ?", EMPLOYEE_CODE);
        jdbc.update("DELETE FROM departments WHERE name = ?", DEPARTMENT_NAME);
    }
}
