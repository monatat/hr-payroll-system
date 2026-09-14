package com.hrpayroll.backend.salary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SalaryPeriodCoverage#covers} -- the shared,
 * Spring/JPA-free coverage rule used by both SalaryStructureService and the
 * future Payroll Engine. Operates on plain dates so it can be tested (and
 * later called from the payroll package) without a SalaryStructure/entity
 * in scope.
 */
class SalaryCoverageAlgorithmTest {

    private static final LocalDate PERIOD_START = LocalDate.of(2026, 9, 1);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 9, 30);

    @Test
    void openEndedStructureStartingBeforePeriodCoversIt() {
        assertThat(SalaryPeriodCoverage.covers(LocalDate.of(2026, 1, 1), null, PERIOD_START, PERIOD_END))
                .isTrue();
    }

    @Test
    void closedStructureFullyContainingPeriodCoversIt() {
        assertThat(SalaryPeriodCoverage.covers(
                        LocalDate.of(2026, 8, 1), LocalDate.of(2026, 10, 31), PERIOD_START, PERIOD_END))
                .isTrue();
    }

    @Test
    void structureStartingAfterPeriodStartDoesNotCoverIt() {
        assertThat(SalaryPeriodCoverage.covers(LocalDate.of(2026, 9, 15), null, PERIOD_START, PERIOD_END))
                .isFalse();
    }

    @Test
    void structureEndingBeforePeriodEndDoesNotCoverIt() {
        assertThat(SalaryPeriodCoverage.covers(
                        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 15), PERIOD_START, PERIOD_END))
                .isFalse();
    }

    @Test
    void structureEndingExactlyOnPeriodEndCoversIt() {
        assertThat(SalaryPeriodCoverage.covers(
                        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 30), PERIOD_START, PERIOD_END))
                .isTrue();
    }

    @Test
    void gapInCoverageMeansNoStructureCoversThePeriod() {
        // Employee has a structure Jan-Aug and another Oct-open; September falls in the gap.
        assertThat(SalaryPeriodCoverage.covers(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 8, 31), PERIOD_START, PERIOD_END))
                .isFalse();
        assertThat(SalaryPeriodCoverage.covers(LocalDate.of(2026, 10, 1), null, PERIOD_START, PERIOD_END))
                .isFalse();
    }

    @Test
    void nullEffectiveToMeansOpenEndedCoverage() {
        assertThat(SalaryPeriodCoverage.covers(LocalDate.of(2026, 9, 1), null, PERIOD_START, PERIOD_END))
                .isTrue();
    }

    @Test
    void nullEffectiveFromIsRejected() {
        assertThatThrownBy(() -> SalaryPeriodCoverage.covers(null, null, PERIOD_START, PERIOD_END))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("effectiveFrom");
    }

    @Test
    void nullPeriodStartIsRejected() {
        assertThatThrownBy(() -> SalaryPeriodCoverage.covers(LocalDate.of(2026, 1, 1), null, null, PERIOD_END))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("periodStart");
    }

    @Test
    void nullPeriodEndIsRejected() {
        assertThatThrownBy(() -> SalaryPeriodCoverage.covers(LocalDate.of(2026, 1, 1), null, PERIOD_START, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("periodEnd");
    }
}
