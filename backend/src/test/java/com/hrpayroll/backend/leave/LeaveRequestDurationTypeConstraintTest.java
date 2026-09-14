package com.hrpayroll.backend.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hrpayroll.backend.support.LeaveTestFixtures;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Narrowly scoped real-PostgreSQL check of the V12 {@code duration_type}
 * column and its two CHECK constraints, via raw JdbcTemplate SQL rather
 * than JPA -- proves the database itself rejects invalid rows independent
 * of application validation (which LeaveRequestControllerTest covers
 * separately). Each rejected INSERT is a single atomic statement, so no
 * partial/invalid row is ever persisted; cleanup() still runs defensively.
 */
@SpringBootTest
class LeaveRequestDurationTypeConstraintTest {

    private static final LocalDate MON = LocalDate.of(2026, 9, 7);
    private static final LocalDate FRI = LocalDate.of(2026, 9, 11);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long employeeId;
    private long annualLeaveTypeId;

    @BeforeEach
    void setUp() {
        LeaveTestFixtures.cleanup(jdbcTemplate);
        long departmentId = LeaveTestFixtures.installDepartment(jdbcTemplate);
        employeeId = LeaveTestFixtures.insertEmployee(jdbcTemplate, departmentId, "DUR", "770101-06-0001");
        annualLeaveTypeId = LeaveTestFixtures.findLeaveTypeIdByName(jdbcTemplate, LeaveTestFixtures.ANNUAL_LEAVE_TYPE_NAME);
    }

    @AfterEach
    void tearDown() {
        LeaveTestFixtures.cleanup(jdbcTemplate);
    }

    @Test
    void existingRowInsertedWithoutAnExplicitDurationTypeReadsAsFullDay() {
        long requestId = LeaveTestFixtures.insertLeaveRequest(
                jdbcTemplate, employeeId, annualLeaveTypeId, MON, FRI, new BigDecimal("5.0"), "PENDING");

        String durationType = jdbcTemplate.queryForObject(
                "SELECT duration_type FROM leave_requests WHERE id = ?", String.class, requestId);

        assertThat(durationType).isEqualTo("FULL_DAY");
    }

    @Test
    void directInsertOfAnUnsupportedDurationTypeIsRejected() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "INSERT INTO leave_requests "
                                + "(employee_id, leave_type_id, start_date, end_date, days_requested, status, duration_type) "
                                + "VALUES (?, ?, ?, ?, ?, 'PENDING', 'WEEK_LONG')",
                        employeeId,
                        annualLeaveTypeId,
                        MON,
                        FRI,
                        new BigDecimal("5.0")))
                .isInstanceOf(DataIntegrityViolationException.class);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM leave_requests WHERE employee_id = ?", Integer.class, employeeId);
        assertThat(count).isZero();
    }

    @Test
    void directInsertOfHalfDayWithDifferentStartAndEndDatesIsRejected() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "INSERT INTO leave_requests "
                                + "(employee_id, leave_type_id, start_date, end_date, days_requested, status, duration_type) "
                                + "VALUES (?, ?, ?, ?, ?, 'PENDING', 'HALF_DAY')",
                        employeeId,
                        annualLeaveTypeId,
                        MON,
                        FRI,
                        new BigDecimal("0.5")))
                .isInstanceOf(DataIntegrityViolationException.class);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM leave_requests WHERE employee_id = ?", Integer.class, employeeId);
        assertThat(count).isZero();
    }
}
