package com.hrpayroll.backend.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.hrpayroll.backend.support.PayrollTestFixtures;
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
 * Proves duplicate-period protection against a REAL PostgreSQL instance, using the real
 * PayrollRunService/PayrollRunTransactionService and the real transaction manager. No mocks, no
 * H2, no Testcontainers, no synchronized blocks, no sleeps.
 *
 * Deliberately NOT annotated @Transactional: fixture rows are inserted via plain
 * (auto-committing) JdbcTemplate calls on the test's own connection, and must be visible to the
 * worker threads' own connections/transactions -- an outer test transaction would hide them until
 * a rollback that never commits. Matches LeaveRequestConcurrencyTest's established style exactly.
 */
@SpringBootTest
class PayrollRunConcurrencyTest {

    @Autowired
    private PayrollRunService payrollRunService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private long adminUserId;

    @BeforeEach
    void setUp() {
        PayrollTestFixtures.cleanup(jdbcTemplate);
        long departmentId = PayrollTestFixtures.installDepartment(jdbcTemplate);
        adminUserId = PayrollTestFixtures.installAdminUser(jdbcTemplate, passwordEncoder);
        long employeeId = PayrollTestFixtures.insertEmployee(
                jdbcTemplate, departmentId, "RACE", "000301010001", LocalDate.of(1999, 1, 1), "ACTIVE");
        PayrollTestFixtures.insertSalaryStructure(
                jdbcTemplate, employeeId, new BigDecimal("1000.00"), BigDecimal.ZERO, BigDecimal.ZERO, LocalDate.of(1999, 1, 1), null);
    }

    @AfterEach
    void tearDown() {
        PayrollTestFixtures.cleanup(jdbcTemplate);
    }

    @Test
    void concurrentCreateAndCalculateForTheSamePeriodExactlyOneSucceeds() throws Exception {
        int month = 3;
        int year = PayrollTestFixtures.CANDIDATE_TEST_PERIOD_YEAR_MAX; // 2005 -- own period, distinct from other test classes

        CountDownLatch bothReady = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Result> task = () -> {
                bothReady.countDown();
                go.await(10, TimeUnit.SECONDS);
                try {
                    PayrollRun run = payrollRunService.createAndCalculate(month, year, adminUserId);
                    return new Result(true, null, run.getId());
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
            long duplicateCount = results.stream()
                    .filter(r -> !r.success && r.failure instanceof PayrollRunPeriodAlreadyExistsException)
                    .count();
            assertThat(successCount).isEqualTo(1);
            assertThat(duplicateCount).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        Integer runCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payroll_runs WHERE period_month = ? AND period_year = ?",
                Integer.class,
                month,
                year);
        assertThat(runCount).isEqualTo(1);

        Long runId = jdbcTemplate.queryForObject(
                "SELECT id FROM payroll_runs WHERE period_month = ? AND period_year = ?", Long.class, month, year);

        Integer payslipCount =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payslips WHERE payroll_run_id = ?", Integer.class, runId);
        assertThat(payslipCount).isEqualTo(1); // exactly one payslip set, no duplicates/partials

        Integer startedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE entity_name = 'PAYROLL_RUN' AND entity_id = ? AND action = 'PAYROLL_RUN_STARTED'",
                Integer.class,
                runId);
        assertThat(startedCount).isEqualTo(1);
    }

    /** Captures the outcome inside the Callable itself rather than letting it cross the Future boundary as an ExecutionException. */
    private record Result(boolean success, RuntimeException failure, Long runId) {
    }
}
