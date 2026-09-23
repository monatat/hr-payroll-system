package com.hrpayroll.backend.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import com.hrpayroll.backend.support.AttendanceTestFixtures;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Proves the Phase 9B-1 concurrency requirements against a REAL PostgreSQL instance, using the
 * real {@link AttendanceService}, the real {@code uq_attendance_employee_work_date} constraint,
 * the real conditional clock-out update, and the real transaction manager. No mocks, no H2, no
 * synchronized blocks, no sleeps used to fake serialization.
 *
 * Deliberately NOT annotated {@code @Transactional} -- matches
 * {@code PayrollRunConcurrencyTest}/{@code SalaryStructureConcurrencyTest}'s established style
 * exactly: fixture rows are inserted via plain (auto-committing) JdbcTemplate calls and must be
 * visible to the worker threads' own connections/transactions.
 */
@SpringBootTest
class AttendanceConcurrencyTest {

    @Autowired
    private AttendanceService attendanceService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private long departmentId;
    private long actorUserId;
    private long employeeId;

    @BeforeEach
    void setUp() {
        AttendanceTestFixtures.cleanup(jdbcTemplate);
        departmentId = AttendanceTestFixtures.installDepartment(jdbcTemplate);
        actorUserId = AttendanceTestFixtures.installAdminUser(jdbcTemplate, passwordEncoder);
        employeeId = AttendanceTestFixtures.insertEmployee(jdbcTemplate, departmentId, "RACE", "770101-01-0099");
    }

    @AfterEach
    void tearDown() {
        AttendanceTestFixtures.cleanup(jdbcTemplate);
    }

    @Test
    void twoConcurrentSameDayClockInsExactlyOneSucceeds() throws Exception {
        CountDownLatch bothReady = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Result> task = () -> {
                bothReady.countDown();
                go.await(10, TimeUnit.SECONDS);
                try {
                    AttendanceResponse response = attendanceService.clockIn(employeeId, actorUserId);
                    return new Result(true, null, response.id());
                } catch (RuntimeException ex) {
                    return new Result(false, ex, null);
                }
            };
            Future<Result> futureA = pool.submit(task);
            Future<Result> futureB = pool.submit(task);

            assertThat(bothReady.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            Result resultA = futureA.get(20, TimeUnit.SECONDS);
            Result resultB = futureB.get(20, TimeUnit.SECONDS);

            List<Result> results = List.of(resultA, resultB);
            long successCount = results.stream().filter(r -> r.success).count();
            long conflictCount = results.stream()
                    .filter(r -> !r.success && r.failure instanceof DataIntegrityViolationException)
                    .count();
            assertThat(successCount).isEqualTo(1);
            assertThat(conflictCount).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM attendance WHERE employee_id = ?", Integer.class, employeeId);
        assertThat(rowCount).isEqualTo(1);

        Long attendanceId =
                jdbcTemplate.queryForObject("SELECT id FROM attendance WHERE employee_id = ?", Long.class, employeeId);
        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE entity_name = 'ATTENDANCE' AND entity_id = ? AND action = 'ATTENDANCE_CLOCKED_IN'",
                Integer.class,
                attendanceId);
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    void twoConcurrentClockOutsForTheSameOpenRowExactlyOneSucceeds() throws Exception {
        AttendanceResponse opened = attendanceService.clockIn(employeeId, actorUserId);

        CountDownLatch bothReady = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Result> task = () -> {
                bothReady.countDown();
                go.await(10, TimeUnit.SECONDS);
                try {
                    AttendanceResponse response = attendanceService.clockOut(employeeId, actorUserId);
                    return new Result(true, null, response.id());
                } catch (RuntimeException ex) {
                    return new Result(false, ex, null);
                }
            };
            Future<Result> futureA = pool.submit(task);
            Future<Result> futureB = pool.submit(task);

            assertThat(bothReady.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            Result resultA = futureA.get(20, TimeUnit.SECONDS);
            Result resultB = futureB.get(20, TimeUnit.SECONDS);

            List<Result> results = List.of(resultA, resultB);
            long successCount = results.stream().filter(r -> r.success).count();
            long conflictCount = results.stream()
                    .filter(r -> !r.success && r.failure instanceof AttendanceAlreadyClockedOutException)
                    .count();
            assertThat(successCount).isEqualTo(1);
            assertThat(conflictCount).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        Integer closedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM attendance WHERE id = ? AND clock_out_at IS NOT NULL",
                Integer.class,
                opened.id());
        assertThat(closedCount).isEqualTo(1);

        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE entity_name = 'ATTENDANCE' AND entity_id = ? AND action = 'ATTENDANCE_CLOCKED_OUT'",
                Integer.class,
                opened.id());
        assertThat(auditCount).isEqualTo(1);
    }

    /** Captures the outcome inside the Callable itself rather than letting it cross the Future boundary as an ExecutionException. */
    private record Result(boolean success, RuntimeException failure, Long attendanceId) {
    }
}
