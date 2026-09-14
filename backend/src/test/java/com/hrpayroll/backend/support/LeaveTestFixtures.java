package com.hrpayroll.backend.support;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Raw-SQL fixtures for Phase 7 leave tests, kept in a separate
 * "phase7.*"/"EMP-PHASE7-*" namespace from earlier phases' fixtures so the
 * suites never collide. Leave balances are managed manually in v1 (no
 * creation API), so tests must explicitly insert whatever balance rows they
 * need -- never rely on incidental/persistent local data.
 */
public final class LeaveTestFixtures {

    public static final String DEPARTMENT_NAME = "Phase7 Test Department";

    public static final String ADMIN_EMAIL = "phase7.admin@test.local";
    public static final String EMPLOYEE_EMAIL = "phase7.employee@test.local";
    public static final String OTHER_EMPLOYEE_EMAIL = "phase7.other-employee@test.local";

    public static final String EMPLOYEE_CODE_PREFIX = "EMP-PHASE7-";

    public static final String ANNUAL_LEAVE_TYPE_NAME = "Annual Leave";
    public static final String SICK_LEAVE_TYPE_NAME = "Sick Leave";

    private LeaveTestFixtures() {
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
                passwordEncoder.encode("Phase7AdminPass123!"));
    }

    public static long insertEmployee(JdbcTemplate jdbc, long departmentId, String codeSuffix, String icNumber) {
        return jdbc.queryForObject(
                "INSERT INTO employees "
                        + "(employee_code, full_name, ic_number, department_id, job_title, employment_status, "
                        + "hire_date, bank_name, bank_account_no, phone_number) "
                        + "VALUES (?, 'Phase7 Test Employee', ?, ?, 'Engineer', 'ACTIVE', CURRENT_DATE, "
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
                passwordEncoder.encode("Phase7EmployeePass123!"),
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
                passwordEncoder.encode("Phase7OtherEmployeePass123!"),
                employeeId);
        return new long[] {employeeId, userId};
    }

    /** Looks up a V11-seeded leave type's id by its name ("Annual Leave" / "Sick Leave"). */
    public static long findLeaveTypeIdByName(JdbcTemplate jdbc, String name) {
        return jdbc.queryForObject("SELECT id FROM leave_types WHERE name = ?", Long.class, name);
    }

    public static long insertLeaveBalance(
            JdbcTemplate jdbc, long employeeId, long leaveTypeId, int year, BigDecimal entitledDays, BigDecimal usedDays) {
        return jdbc.queryForObject(
                "INSERT INTO leave_balances (employee_id, leave_type_id, year, entitled_days, used_days) "
                        + "VALUES (?, ?, ?, ?, ?) RETURNING id",
                Long.class,
                employeeId,
                leaveTypeId,
                year,
                entitledDays,
                usedDays);
    }

    /** Directly inserts a leave request with an explicit status (e.g. APPROVED/REJECTED) -- no approval endpoint exists yet. */
    public static long insertLeaveRequest(
            JdbcTemplate jdbc,
            long employeeId,
            long leaveTypeId,
            LocalDate startDate,
            LocalDate endDate,
            BigDecimal daysRequested,
            String status) {
        return jdbc.queryForObject(
                "INSERT INTO leave_requests (employee_id, leave_type_id, start_date, end_date, days_requested, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?) RETURNING id",
                Long.class,
                employeeId,
                leaveTypeId,
                startDate,
                endDate,
                daysRequested,
                status);
    }

    /** Directly inserts a HALF_DAY leave request (single date, 0.5 days) with an explicit status. */
    public static long insertHalfDayLeaveRequest(
            JdbcTemplate jdbc, long employeeId, long leaveTypeId, LocalDate date, String status) {
        return jdbc.queryForObject(
                "INSERT INTO leave_requests "
                        + "(employee_id, leave_type_id, start_date, end_date, days_requested, status, duration_type) "
                        + "VALUES (?, ?, ?, ?, 0.5, ?, 'HALF_DAY') RETURNING id",
                Long.class,
                employeeId,
                leaveTypeId,
                date,
                date,
                status);
    }

    /** As above, but with an explicit created_at -- used to test the id-DESC pagination tiebreaker deterministically. */
    public static long insertLeaveRequestWithCreatedAt(
            JdbcTemplate jdbc,
            long employeeId,
            long leaveTypeId,
            LocalDate startDate,
            LocalDate endDate,
            BigDecimal daysRequested,
            String status,
            LocalDateTime createdAt) {
        return jdbc.queryForObject(
                "INSERT INTO leave_requests "
                        + "(employee_id, leave_type_id, start_date, end_date, days_requested, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
                Long.class,
                employeeId,
                leaveTypeId,
                startDate,
                endDate,
                daysRequested,
                status,
                createdAt,
                createdAt);
    }

    /** Safe to call even if nothing exists. Never touches leave_types (seeded once by V11, read-only). */
    public static void cleanup(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM users WHERE email IN (?, ?, ?)", ADMIN_EMAIL, EMPLOYEE_EMAIL, OTHER_EMPLOYEE_EMAIL);
        jdbc.update(
                "DELETE FROM leave_requests WHERE employee_id IN "
                        + "(SELECT id FROM employees WHERE employee_code LIKE ?)",
                EMPLOYEE_CODE_PREFIX + "%");
        jdbc.update(
                "DELETE FROM leave_balances WHERE employee_id IN "
                        + "(SELECT id FROM employees WHERE employee_code LIKE ?)",
                EMPLOYEE_CODE_PREFIX + "%");
        jdbc.update("DELETE FROM employees WHERE employee_code LIKE ?", EMPLOYEE_CODE_PREFIX + "%");
        jdbc.update("DELETE FROM departments WHERE name = ?", DEPARTMENT_NAME);
    }
}
