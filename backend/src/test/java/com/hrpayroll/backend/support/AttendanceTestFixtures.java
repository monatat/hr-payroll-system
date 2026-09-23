package com.hrpayroll.backend.support;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Raw-SQL fixtures for Phase 9 attendance tests, kept in a separate
 * "Phase9"/"EMP-PHASE9-" namespace from earlier phases' fixtures so the
 * suites never collide.
 */
public final class AttendanceTestFixtures {

    public static final String DEPARTMENT_NAME = "Phase9 Test Department";

    public static final String EMPLOYEE_CODE_PREFIX = "EMP-PHASE9-";

    public static final String ADMIN_EMAIL = "phase9.admin@test.local";

    private AttendanceTestFixtures() {
    }

    public static long installDepartment(JdbcTemplate jdbc) {
        return jdbc.queryForObject("INSERT INTO departments (name) VALUES (?) RETURNING id", Long.class, DEPARTMENT_NAME);
    }

    /** Real users row so audit_logs.actor_id's FK is satisfiable in tests that verify audit content. */
    public static long installAdminUser(JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
        return jdbc.queryForObject(
                "INSERT INTO users (email, password_hash, role, account_status, employee_id) "
                        + "VALUES (?, ?, 'HR_ADMIN', 'ACTIVE', NULL) RETURNING id",
                Long.class,
                ADMIN_EMAIL,
                passwordEncoder.encode("Phase9AdminPass123!"));
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

    /** Raw-SQL insert bypassing AttendanceService entirely -- used to plant a stale prior-day row. */
    public static long insertAttendance(
            JdbcTemplate jdbc, long employeeId, LocalDate workDate, LocalDateTime clockInAt, LocalDateTime clockOutAt) {
        return jdbc.queryForObject(
                "INSERT INTO attendance (employee_id, work_date, clock_in_at, clock_out_at) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class,
                employeeId,
                workDate,
                clockInAt,
                clockOutAt);
    }

    /** Safe to call even if nothing exists. Never touches audit_logs (append-only, retained). */
    public static void cleanup(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM users WHERE email = ?", ADMIN_EMAIL);
        jdbc.update(
                "DELETE FROM attendance WHERE employee_id IN "
                        + "(SELECT id FROM employees WHERE employee_code LIKE ?)",
                EMPLOYEE_CODE_PREFIX + "%");
        jdbc.update("DELETE FROM employees WHERE employee_code LIKE ?", EMPLOYEE_CODE_PREFIX + "%");
        jdbc.update("DELETE FROM departments WHERE name = ?", DEPARTMENT_NAME);
    }
}
