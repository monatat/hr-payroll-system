package com.hrpayroll.backend.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.hrpayroll.backend.support.PayrollTestFixtures;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Real-PostgreSQL JPA mapping/repository tests for {@link PayrollRun}. No
 * service/orchestration layer exists yet in this sub-phase, so these
 * exercise the entity and repository directly -- the correct layer to test
 * at when there is nothing above it yet. Rows are saved directly via the
 * repository, bypassing the audited PayrollRunService, so each saved run's
 * id is tracked explicitly and passed to cleanup -- never cleaned up by
 * period_year alone (see PayrollTestFixturesCleanupScopeTest).
 */
@SpringBootTest
class PayrollRunRepositoryTest {

    @Autowired
    private PayrollRunRepository payrollRunRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> createdRunIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        createdRunIds.clear();
        PayrollTestFixtures.cleanup(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        PayrollTestFixtures.cleanup(jdbcTemplate, createdRunIds);
    }

    private PayrollRun saveTestRun(int periodMonth) {
        PayrollRun saved = payrollRunRepository.saveAndFlush(newTestRun(periodMonth));
        createdRunIds.add(saved.getId());
        return saved;
    }

    private static PayrollRun newTestRun(int periodMonth) {
        LocalDate periodStart = LocalDate.of(PayrollTestFixtures.TEST_PERIOD_YEAR, periodMonth, 1);
        LocalDate periodEnd = periodStart.withDayOfMonth(periodStart.lengthOfMonth());
        return new PayrollRun(
                periodMonth,
                PayrollTestFixtures.TEST_PERIOD_YEAR,
                periodStart,
                periodEnd,
                PayrollRunStatus.PROCESSING,
                new BigDecimal("0.1100"),
                new BigDecimal("0.0050"),
                new BigDecimal("0.0020"),
                new BigDecimal("0.0500"));
    }

    @Test
    void savedRunRoundTripsStatusEnumAndRateSnapshotsExactly() {
        PayrollRun saved = saveTestRun(2);

        PayrollRun reloaded = payrollRunRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getStatus()).isEqualTo(PayrollRunStatus.PROCESSING);
        assertThat(reloaded.getEpfRateSnapshot()).isEqualByComparingTo("0.1100");
        assertThat(reloaded.getSocsoRateSnapshot()).isEqualByComparingTo("0.0050");
        assertThat(reloaded.getEisRateSnapshot()).isEqualByComparingTo("0.0020");
        assertThat(reloaded.getIncomeTaxRateSnapshot()).isEqualByComparingTo("0.0500");
        assertThat(reloaded.getVersion()).isEqualTo(0L);
    }

    @Test
    void findByPeriodMonthAndPeriodYearFindsAnExistingRun() {
        saveTestRun(3);

        Optional<PayrollRun> found =
                payrollRunRepository.findByPeriodMonthAndPeriodYear(3, PayrollTestFixtures.TEST_PERIOD_YEAR);

        assertThat(found).isPresent();
        assertThat(found.get().getPeriodMonth()).isEqualTo(3);
    }

    @Test
    void findByPeriodMonthAndPeriodYearReturnsEmptyWhenNoRunExists() {
        Optional<PayrollRun> found =
                payrollRunRepository.findByPeriodMonthAndPeriodYear(4, PayrollTestFixtures.TEST_PERIOD_YEAR);

        assertThat(found).isEmpty();
    }

    @Test
    void existsByPeriodMonthAndPeriodYearReflectsWhetherARunExists() {
        assertThat(payrollRunRepository.existsByPeriodMonthAndPeriodYear(5, PayrollTestFixtures.TEST_PERIOD_YEAR))
                .isFalse();

        saveTestRun(5);

        assertThat(payrollRunRepository.existsByPeriodMonthAndPeriodYear(5, PayrollTestFixtures.TEST_PERIOD_YEAR))
                .isTrue();
    }

    /**
     * PESSIMISTIC_WRITE requires an active transaction (Hibernate throws
     * TransactionRequiredException otherwise) -- production callers always
     * invoke findByIdForUpdate from within an existing @Transactional
     * service method (see EmployeeRepository.findByIdForUpdate's callers);
     * with no service layer yet in this sub-phase, this test provides that
     * transaction directly. This does not test concurrent locking behavior
     * (that belongs to Phase 8B's real-PostgreSQL concurrency test) -- only
     * that the method compiles, executes, and returns the correct row.
     */
    @Test
    @Transactional
    void findByIdForUpdateReturnsTheLockedRow() {
        PayrollRun saved = saveTestRun(6);

        Optional<PayrollRun> locked = payrollRunRepository.findByIdForUpdate(saved.getId());

        assertThat(locked).isPresent();
        assertThat(locked.get().getId()).isEqualTo(saved.getId());
        assertThat(locked.get().getPeriodMonth()).isEqualTo(6);
    }
}
