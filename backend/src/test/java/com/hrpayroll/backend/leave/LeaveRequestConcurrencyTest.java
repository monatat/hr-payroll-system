package com.hrpayroll.backend.leave;

import static org.assertj.core.api.Assertions.assertThat;

import com.hrpayroll.backend.support.LeaveTestFixtures;
import java.math.BigDecimal;
import java.time.LocalDate;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Proves the three Phase 7B-2 concurrency requirements against a REAL
 * PostgreSQL instance, using the real LeaveRequestService, real
 * repositories (including EmployeeRepository.findByIdForUpdate and
 * LeaveBalanceRepository.findForUpdate), and the real transaction manager.
 * No mocks, no H2, no Testcontainers, no synchronized blocks, no sleeps.
 *
 * Deliberately NOT annotated @Transactional: fixture rows are inserted via
 * plain (auto-committing) JdbcTemplate calls on the test's own connection,
 * and must be visible to the worker threads' own connections/transactions
 * -- an outer test transaction would hide them until a rollback that never
 * commits.
 */
@SpringBootTest
class LeaveRequestConcurrencyTest {

    private static final LocalDate MON = LocalDate.of(2026, 9, 7);
    private static final LocalDate FRI = LocalDate.of(2026, 9, 11);
    private static final LocalDate NEXT_MON = LocalDate.of(2026, 9, 14);
    private static final LocalDate NEXT_FRI = LocalDate.of(2026, 9, 18);
    private static final BigDecimal FIVE = new BigDecimal("5.0");

    @Autowired
    private LeaveRequestService leaveRequestService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private long employeeId;
    private long annualLeaveTypeId;
    private long adminUserId;

    @BeforeEach
    void setUp() {
        LeaveTestFixtures.cleanup(jdbcTemplate);
        long departmentId = LeaveTestFixtures.installDepartment(jdbcTemplate);
        adminUserId = LeaveTestFixtures.installAdminUser(jdbcTemplate, passwordEncoder);
        employeeId = LeaveTestFixtures.insertEmployee(jdbcTemplate, departmentId, "CONC", "770101-05-0001");
        annualLeaveTypeId = LeaveTestFixtures.findLeaveTypeIdByName(jdbcTemplate, LeaveTestFixtures.ANNUAL_LEAVE_TYPE_NAME);
    }

    @AfterEach
    void tearDown() {
        LeaveTestFixtures.cleanup(jdbcTemplate);
    }

    // ---- Race 1: same request approved concurrently ----

    @Test
    void concurrentApprovalOfTheSameRequestExactlyOneSucceeds() throws Exception {
        long requestId =
                LeaveTestFixtures.insertLeaveRequest(jdbcTemplate, employeeId, annualLeaveTypeId, MON, FRI, FIVE, "PENDING");
        LeaveTestFixtures.insertLeaveBalance(jdbcTemplate, employeeId, annualLeaveTypeId, 2026, new BigDecimal("14.0"), BigDecimal.ZERO);

        CountDownLatch bothReady = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Result> task = approveTask(requestId, bothReady, go);
            Future<Result> futureA = pool.submit(task);
            Future<Result> futureB = pool.submit(task);

            assertThat(bothReady.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            Result resultA = futureA.get(15, TimeUnit.SECONDS);
            Result resultB = futureB.get(15, TimeUnit.SECONDS);

            List<Result> results = List.of(resultA, resultB);
            long successCount = results.stream().filter(r -> r.success).count();
            long conflictCount = results.stream()
                    .filter(r -> !r.success && r.failure instanceof LeaveRequestAlreadyProcessedException)
                    .count();
            assertThat(successCount).isEqualTo(1);
            assertThat(conflictCount).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        String status = jdbcTemplate.queryForObject("SELECT status FROM leave_requests WHERE id = ?", String.class, requestId);
        Long version = jdbcTemplate.queryForObject("SELECT version FROM leave_requests WHERE id = ?", Long.class, requestId);
        BigDecimal usedDays = jdbcTemplate.queryForObject(
                "SELECT used_days FROM leave_balances WHERE employee_id = ? AND leave_type_id = ? AND year = 2026",
                BigDecimal.class,
                employeeId,
                annualLeaveTypeId);
        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = 'LEAVE_REQUEST_APPROVED' AND entity_id = ?",
                Integer.class,
                requestId);

        assertThat(status).isEqualTo("APPROVED");
        assertThat(version).isEqualTo(1L); // advanced exactly once from the initial 0
        assertThat(usedDays).isEqualByComparingTo(FIVE); // used_days increased exactly once
        assertThat(auditCount).isEqualTo(1);
    }

    // ---- Race 2: different requests competing for one balance ----

    @Test
    void concurrentApprovalsCompetingForOneBalanceExactlyOneSucceeds() throws Exception {
        long requestAId =
                LeaveTestFixtures.insertLeaveRequest(jdbcTemplate, employeeId, annualLeaveTypeId, MON, FRI, FIVE, "PENDING");
        long requestBId = LeaveTestFixtures.insertLeaveRequest(
                jdbcTemplate, employeeId, annualLeaveTypeId, NEXT_MON, NEXT_FRI, FIVE, "PENDING");
        // Balance can satisfy exactly one of the two 5-day requests, not both.
        LeaveTestFixtures.insertLeaveBalance(jdbcTemplate, employeeId, annualLeaveTypeId, 2026, FIVE, BigDecimal.ZERO);

        CountDownLatch bothReady = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Result> futureA = pool.submit(approveTask(requestAId, bothReady, go));
            Future<Result> futureB = pool.submit(approveTask(requestBId, bothReady, go));

            assertThat(bothReady.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            Result resultA = futureA.get(15, TimeUnit.SECONDS);
            Result resultB = futureB.get(15, TimeUnit.SECONDS);

            List<Result> results = List.of(resultA, resultB);
            long successCount = results.stream().filter(r -> r.success).count();
            long insufficientCount = results.stream()
                    .filter(r -> !r.success && r.failure instanceof InsufficientLeaveBalanceException)
                    .count();
            assertThat(successCount).isEqualTo(1);
            assertThat(insufficientCount).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        BigDecimal usedDays = jdbcTemplate.queryForObject(
                "SELECT used_days FROM leave_balances WHERE employee_id = ? AND leave_type_id = ? AND year = 2026",
                BigDecimal.class,
                employeeId,
                annualLeaveTypeId);
        assertThat(usedDays).isEqualByComparingTo(FIVE); // never exceeds entitledDays (5.0)

        String statusA = jdbcTemplate.queryForObject("SELECT status FROM leave_requests WHERE id = ?", String.class, requestAId);
        String statusB = jdbcTemplate.queryForObject("SELECT status FROM leave_requests WHERE id = ?", String.class, requestBId);
        List<String> statuses = List.of(statusA, statusB);
        assertThat(statuses.stream().filter("APPROVED"::equals).count()).isEqualTo(1);
        assertThat(statuses.stream().filter("PENDING"::equals).count()).isEqualTo(1); // loser stays PENDING, not rejected

        Integer approvalAuditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = 'LEAVE_REQUEST_APPROVED' AND entity_id IN (?, ?)",
                Integer.class,
                requestAId,
                requestBId);
        assertThat(approvalAuditCount).isEqualTo(1);
    }

    // ---- Race 3: concurrent overlapping submissions ----

    @Test
    void concurrentOverlappingSubmissionsExactlyOneSucceeds() throws Exception {
        CreateLeaveRequestRequest requestA = new CreateLeaveRequestRequest(annualLeaveTypeId, MON, FRI, null);
        // Overlaps requestA: Sep 9 falls inside Sep 7-11.
        CreateLeaveRequestRequest requestB =
                new CreateLeaveRequestRequest(annualLeaveTypeId, LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 15), null);

        CountDownLatch bothReady = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Result> futureA = pool.submit(createTask(requestA, bothReady, go));
            Future<Result> futureB = pool.submit(createTask(requestB, bothReady, go));

            assertThat(bothReady.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            Result resultA = futureA.get(15, TimeUnit.SECONDS);
            Result resultB = futureB.get(15, TimeUnit.SECONDS);

            List<Result> results = List.of(resultA, resultB);
            long successCount = results.stream().filter(r -> r.success).count();
            long overlapCount = results.stream()
                    .filter(r -> !r.success && r.failure instanceof LeaveRequestOverlapException)
                    .count();
            assertThat(successCount).isEqualTo(1);
            assertThat(overlapCount).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        Integer activeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM leave_requests WHERE employee_id = ? AND status IN ('PENDING', 'APPROVED')",
                Integer.class,
                employeeId);
        assertThat(activeCount).isEqualTo(1);
    }

    // ---- Race 4: concurrent FULL_DAY vs HALF_DAY creation for the same date ----

    @Test
    void concurrentFullDayAndHalfDayCreationForTheSameDateExactlyOneSucceeds() throws Exception {
        CreateLeaveRequestRequest fullDayRequest = new CreateLeaveRequestRequest(annualLeaveTypeId, MON, FRI, null);
        CreateLeaveRequestRequest halfDayRequest =
                new CreateLeaveRequestRequest(annualLeaveTypeId, MON, MON, null, LeaveDurationType.HALF_DAY);

        CountDownLatch bothReady = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Result> futureA = pool.submit(createTask(fullDayRequest, bothReady, go));
            Future<Result> futureB = pool.submit(createTask(halfDayRequest, bothReady, go));

            assertThat(bothReady.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            Result resultA = futureA.get(15, TimeUnit.SECONDS);
            Result resultB = futureB.get(15, TimeUnit.SECONDS);

            List<Result> results = List.of(resultA, resultB);
            long successCount = results.stream().filter(r -> r.success).count();
            long overlapCount = results.stream()
                    .filter(r -> !r.success && r.failure instanceof LeaveRequestOverlapException)
                    .count();
            assertThat(successCount).isEqualTo(1);
            assertThat(overlapCount).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        Integer activeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM leave_requests WHERE employee_id = ? AND status IN ('PENDING', 'APPROVED')",
                Integer.class,
                employeeId);
        assertThat(activeCount).isEqualTo(1);
    }

    // ---- Race 5: concurrent HALF_DAY approvals competing for a balance with exactly 0.5 remaining ----

    @Test
    void concurrentHalfDayApprovalsCompetingForABalanceWithExactlyHalfDayRemainingExactlyOneSucceeds() throws Exception {
        long requestAId = LeaveTestFixtures.insertHalfDayLeaveRequest(jdbcTemplate, employeeId, annualLeaveTypeId, MON, "PENDING");
        long requestBId =
                LeaveTestFixtures.insertHalfDayLeaveRequest(jdbcTemplate, employeeId, annualLeaveTypeId, NEXT_MON, "PENDING");
        // Balance can satisfy exactly one of the two 0.5-day requests, not both.
        LeaveTestFixtures.insertLeaveBalance(jdbcTemplate, employeeId, annualLeaveTypeId, 2026, new BigDecimal("0.5"), BigDecimal.ZERO);

        CountDownLatch bothReady = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Result> futureA = pool.submit(approveTask(requestAId, bothReady, go));
            Future<Result> futureB = pool.submit(approveTask(requestBId, bothReady, go));

            assertThat(bothReady.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            Result resultA = futureA.get(15, TimeUnit.SECONDS);
            Result resultB = futureB.get(15, TimeUnit.SECONDS);

            List<Result> results = List.of(resultA, resultB);
            long successCount = results.stream().filter(r -> r.success).count();
            long insufficientCount = results.stream()
                    .filter(r -> !r.success && r.failure instanceof InsufficientLeaveBalanceException)
                    .count();
            assertThat(successCount).isEqualTo(1);
            assertThat(insufficientCount).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        BigDecimal usedDays = jdbcTemplate.queryForObject(
                "SELECT used_days FROM leave_balances WHERE employee_id = ? AND leave_type_id = ? AND year = 2026",
                BigDecimal.class,
                employeeId,
                annualLeaveTypeId);
        assertThat(usedDays).isEqualByComparingTo("0.5");
        assertThat(usedDays).isLessThanOrEqualTo(new BigDecimal("0.5")); // never exceeds entitledDays (0.5)

        String statusA = jdbcTemplate.queryForObject("SELECT status FROM leave_requests WHERE id = ?", String.class, requestAId);
        String statusB = jdbcTemplate.queryForObject("SELECT status FROM leave_requests WHERE id = ?", String.class, requestBId);
        List<String> statuses = List.of(statusA, statusB);
        assertThat(statuses.stream().filter("APPROVED"::equals).count()).isEqualTo(1);
        assertThat(statuses.stream().filter("PENDING"::equals).count()).isEqualTo(1); // loser stays PENDING, not rejected

        Integer approvalAuditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = 'LEAVE_REQUEST_APPROVED' AND entity_id IN (?, ?)",
                Integer.class,
                requestAId,
                requestBId);
        assertThat(approvalAuditCount).isEqualTo(1);
    }

    private Callable<Result> approveTask(Long requestId, CountDownLatch bothReady, CountDownLatch go) {
        return () -> {
            bothReady.countDown();
            go.await(10, TimeUnit.SECONDS);
            try {
                leaveRequestService.approve(requestId, adminUserId);
                return new Result(true, null);
            } catch (RuntimeException ex) {
                return new Result(false, ex);
            }
        };
    }

    private Callable<Result> createTask(CreateLeaveRequestRequest request, CountDownLatch bothReady, CountDownLatch go) {
        return () -> {
            bothReady.countDown();
            go.await(10, TimeUnit.SECONDS);
            try {
                leaveRequestService.create(employeeId, request, adminUserId);
                return new Result(true, null);
            } catch (RuntimeException ex) {
                return new Result(false, ex);
            }
        };
    }

    /** Captures the outcome inside the Callable itself rather than letting it cross the Future boundary as an ExecutionException. */
    private record Result(boolean success, RuntimeException failure) {
    }
}
