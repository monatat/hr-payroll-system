package com.hrpayroll.backend.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hrpayroll.backend.support.PayrollTestFixtures;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Narrowly scoped real-PostgreSQL check of the V13 {@code payroll_runs}
 * columns and their CHECK constraints, via raw JdbcTemplate SQL rather than
 * JPA -- proves the database itself rejects invalid rows independent of any
 * future application-level validation. Each rejected INSERT is a single
 * atomic statement, so no partial/invalid row is ever persisted.
 */
@SpringBootTest
class PayrollRunConstraintTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        PayrollTestFixtures.cleanup(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        PayrollTestFixtures.cleanup(jdbcTemplate);
    }

    @Test
    void insertingWithoutV13ColumnsReadsBackAllFourDemoDefaultsAndVersionZero() {
        long id = PayrollTestFixtures.insertPayrollRunWithoutV13Columns(
                jdbcTemplate, 1, PayrollTestFixtures.TEST_PERIOD_YEAR);

        BigDecimal epfRate = jdbcTemplate.queryForObject(
                "SELECT epf_rate_snapshot FROM payroll_runs WHERE id = ?", BigDecimal.class, id);
        BigDecimal socsoRate = jdbcTemplate.queryForObject(
                "SELECT socso_rate_snapshot FROM payroll_runs WHERE id = ?", BigDecimal.class, id);
        BigDecimal eisRate = jdbcTemplate.queryForObject(
                "SELECT eis_rate_snapshot FROM payroll_runs WHERE id = ?", BigDecimal.class, id);
        BigDecimal incomeTaxRate = jdbcTemplate.queryForObject(
                "SELECT income_tax_rate_snapshot FROM payroll_runs WHERE id = ?", BigDecimal.class, id);
        Long version = jdbcTemplate.queryForObject("SELECT version FROM payroll_runs WHERE id = ?", Long.class, id);

        assertThat(epfRate).isEqualByComparingTo("0.1100");
        assertThat(socsoRate).isEqualByComparingTo("0.0050");
        assertThat(eisRate).isEqualByComparingTo("0.0020");
        assertThat(incomeTaxRate).isEqualByComparingTo("0.0500");
        assertThat(version).isEqualTo(0L);
    }

    @Test
    void periodYearBelow2000IsRejected() {
        assertThatThrownBy(() -> PayrollTestFixtures.insertPayrollRunWithPeriodYear(jdbcTemplate, 1, 1999))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(countTestRows()).isZero();
    }

    @Test
    void periodYearAbove2100IsRejected() {
        assertThatThrownBy(() -> PayrollTestFixtures.insertPayrollRunWithPeriodYear(jdbcTemplate, 1, 2101))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(countTestRows()).isZero();
    }

    @Test
    void negativeEpfRateSnapshotIsRejected() {
        assertThatThrownBy(() -> PayrollTestFixtures.insertPayrollRunWithRates(
                        jdbcTemplate,
                        1,
                        PayrollTestFixtures.TEST_PERIOD_YEAR,
                        new BigDecimal("-0.0001"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(countTestRows()).isZero();
    }

    @Test
    void negativeSocsoRateSnapshotIsRejected() {
        assertThatThrownBy(() -> PayrollTestFixtures.insertPayrollRunWithRates(
                        jdbcTemplate,
                        1,
                        PayrollTestFixtures.TEST_PERIOD_YEAR,
                        BigDecimal.ZERO,
                        new BigDecimal("-0.0001"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(countTestRows()).isZero();
    }

    @Test
    void negativeEisRateSnapshotIsRejected() {
        assertThatThrownBy(() -> PayrollTestFixtures.insertPayrollRunWithRates(
                        jdbcTemplate,
                        1,
                        PayrollTestFixtures.TEST_PERIOD_YEAR,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        new BigDecimal("-0.0001"),
                        BigDecimal.ZERO))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(countTestRows()).isZero();
    }

    @Test
    void negativeIncomeTaxRateSnapshotIsRejected() {
        assertThatThrownBy(() -> PayrollTestFixtures.insertPayrollRunWithRates(
                        jdbcTemplate,
                        1,
                        PayrollTestFixtures.TEST_PERIOD_YEAR,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        new BigDecimal("-0.0001")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(countTestRows()).isZero();
    }

    @Test
    void epfRateSnapshotAboveOneIsRejected() {
        assertThatThrownBy(() -> PayrollTestFixtures.insertPayrollRunWithRates(
                        jdbcTemplate,
                        1,
                        PayrollTestFixtures.TEST_PERIOD_YEAR,
                        new BigDecimal("1.0001"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(countTestRows()).isZero();
    }

    @Test
    void socsoRateSnapshotAboveOneIsRejected() {
        assertThatThrownBy(() -> PayrollTestFixtures.insertPayrollRunWithRates(
                        jdbcTemplate,
                        1,
                        PayrollTestFixtures.TEST_PERIOD_YEAR,
                        BigDecimal.ZERO,
                        new BigDecimal("1.0001"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(countTestRows()).isZero();
    }

    @Test
    void eisRateSnapshotAboveOneIsRejected() {
        assertThatThrownBy(() -> PayrollTestFixtures.insertPayrollRunWithRates(
                        jdbcTemplate,
                        1,
                        PayrollTestFixtures.TEST_PERIOD_YEAR,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        new BigDecimal("1.0001"),
                        BigDecimal.ZERO))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(countTestRows()).isZero();
    }

    @Test
    void incomeTaxRateSnapshotAboveOneIsRejected() {
        assertThatThrownBy(() -> PayrollTestFixtures.insertPayrollRunWithRates(
                        jdbcTemplate,
                        1,
                        PayrollTestFixtures.TEST_PERIOD_YEAR,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        new BigDecimal("1.0001")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(countTestRows()).isZero();
    }

    @Test
    void combinedRateSnapshotTotalAboveOneIsRejected() {
        assertThatThrownBy(() -> PayrollTestFixtures.insertPayrollRunWithRates(
                        jdbcTemplate,
                        1,
                        PayrollTestFixtures.TEST_PERIOD_YEAR,
                        new BigDecimal("0.50"),
                        new BigDecimal("0.30"),
                        new BigDecimal("0.15"),
                        new BigDecimal("0.10")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(countTestRows()).isZero();
    }

    @Test
    void combinedRateSnapshotTotalExactlyOneIsAccepted() {
        PayrollTestFixtures.insertPayrollRunWithRates(
                jdbcTemplate,
                1,
                PayrollTestFixtures.TEST_PERIOD_YEAR,
                new BigDecimal("0.25"),
                new BigDecimal("0.25"),
                new BigDecimal("0.25"),
                new BigDecimal("0.25"));

        assertThat(countTestRows()).isEqualTo(1);
    }

    private Integer countTestRows() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payroll_runs WHERE period_year = ?",
                Integer.class,
                PayrollTestFixtures.TEST_PERIOD_YEAR);
    }
}
