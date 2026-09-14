package com.hrpayroll.backend.salary;

import static org.assertj.core.api.Assertions.assertThat;

import com.hrpayroll.backend.support.SalaryStructureTestFixtures;
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
 * Proves the Phase 6 concurrency requirement against a REAL PostgreSQL
 * instance: two concurrent transactions both calling the real
 * {@link SalaryStructureService#create}, using the real
 * {@code EmployeeRepository.findByIdForUpdate} PESSIMISTIC_WRITE lock, the
 * real {@link SalaryStructureRepository}, and the real transaction manager.
 * No mocks, no synchronized blocks, no application-only locking, no sleeps
 * used to fake serialization -- the PostgreSQL row lock is what actually
 * serializes the two threads.
 */
@SpringBootTest
class SalaryStructureConcurrencyTest {

    @Autowired
    private SalaryStructureService salaryStructureService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private long departmentId;
    private long employeeId;

    @BeforeEach
    void setUp() {
        SalaryStructureTestFixtures.cleanup(jdbcTemplate);
        departmentId = SalaryStructureTestFixtures.installDepartment(jdbcTemplate);
        employeeId = SalaryStructureTestFixtures.insertEmployee(jdbcTemplate, departmentId, "CONC", "770101-01-0099");
    }

    @AfterEach
    void tearDown() {
        SalaryStructureTestFixtures.cleanup(jdbcTemplate);
    }

    private CreateSalaryStructureRequest request(String basePay, LocalDate from, LocalDate to) {
        return new CreateSalaryStructureRequest(new BigDecimal(basePay), null, null, from, to);
    }

    @Test
    void concurrentOverlappingCreatesForSameEmployeeSerializeAndExactlyOneSucceeds() throws Exception {
        // Both ranges are CLOSED and genuinely overlap (Mar 1 falls inside
        // Jan 1..Jun 30) -- neither is open-ended, so the succession
        // transition (Rule 3) cannot apply to either side. This isolates a
        // real conflict from the (also-valid) concurrent-succession case
        // covered by the second test below.
        CountDownLatch bothReady = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Result> taskA = concurrentCreateTask(
                    bothReady, go, request("5000.00", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30)));
            Callable<Result> taskB = concurrentCreateTask(
                    bothReady, go, request("6000.00", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 8, 31)));

            Future<Result> futureA = pool.submit(taskA);
            Future<Result> futureB = pool.submit(taskB);

            // Release both threads at (as close to) the same instant as possible.
            assertThat(bothReady.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            Result resultA = futureA.get(15, TimeUnit.SECONDS);
            Result resultB = futureB.get(15, TimeUnit.SECONDS);

            List<Result> results = List.of(resultA, resultB);
            long successCount = results.stream().filter(r -> r.success).count();
            long conflictCount = results.stream()
                    .filter(r -> !r.success && r.failure instanceof SalaryStructureOverlapException)
                    .count();

            assertThat(successCount).isEqualTo(1);
            assertThat(conflictCount).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        // Ground truth from the real database, not from application state.
        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM salary_structures WHERE employee_id = ?", Integer.class, employeeId);
        assertThat(rowCount).isEqualTo(1);
    }

    @Test
    void concurrentNonOverlappingAdjacentCreatesForSameEmployeeBothSucceed() throws Exception {
        CountDownLatch bothReady = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Result> taskA = concurrentCreateTask(
                    bothReady, go, request("5000.00", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30)));
            Callable<Result> taskB = concurrentCreateTask(
                    bothReady, go, request("5500.00", LocalDate.of(2026, 7, 1), null));

            Future<Result> futureA = pool.submit(taskA);
            Future<Result> futureB = pool.submit(taskB);

            assertThat(bothReady.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            Result resultA = futureA.get(15, TimeUnit.SECONDS);
            Result resultB = futureB.get(15, TimeUnit.SECONDS);

            assertThat(resultA.success).isTrue();
            assertThat(resultB.success).isTrue();
        } finally {
            pool.shutdownNow();
        }

        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM salary_structures WHERE employee_id = ?", Integer.class, employeeId);
        assertThat(rowCount).isEqualTo(2);
    }

    private Callable<Result> concurrentCreateTask(
            CountDownLatch bothReady, CountDownLatch go, CreateSalaryStructureRequest request) {
        return () -> {
            bothReady.countDown();
            go.await(10, TimeUnit.SECONDS);
            try {
                salaryStructureService.create(employeeId, request, null);
                return new Result(true, null);
            } catch (RuntimeException ex) {
                return new Result(false, ex);
            }
        };
    }

    private record Result(boolean success, RuntimeException failure) {
    }
}
