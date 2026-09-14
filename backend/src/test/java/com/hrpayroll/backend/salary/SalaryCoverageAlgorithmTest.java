package com.hrpayroll.backend.salary;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SalaryStructureService#covers} -- the future
 * Payroll Engine's coverage rule (Phase 6 only exposes/tests this helper;
 * it does not implement payroll calculation itself).
 */
class SalaryCoverageAlgorithmTest {

    private static final LocalDate PERIOD_START = LocalDate.of(2026, 9, 1);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 9, 30);

    // employee is null -- covers() never dereferences it, only the dates.
    private static SalaryStructure structure(LocalDate from, LocalDate to) {
        return new SalaryStructure(null, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, from, to);
    }

    @Test
    void openEndedStructureStartingBeforePeriodCoversIt() {
        assertThat(SalaryStructureService.covers(structure(LocalDate.of(2026, 1, 1), null), PERIOD_START, PERIOD_END))
                .isTrue();
    }

    @Test
    void closedStructureFullyContainingPeriodCoversIt() {
        assertThat(SalaryStructureService.covers(
                        structure(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 10, 31)), PERIOD_START, PERIOD_END))
                .isTrue();
    }

    @Test
    void structureStartingAfterPeriodStartDoesNotCoverIt() {
        assertThat(SalaryStructureService.covers(structure(LocalDate.of(2026, 9, 15), null), PERIOD_START, PERIOD_END))
                .isFalse();
    }

    @Test
    void structureEndingBeforePeriodEndDoesNotCoverIt() {
        assertThat(SalaryStructureService.covers(
                        structure(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 15)), PERIOD_START, PERIOD_END))
                .isFalse();
    }

    @Test
    void structureEndingExactlyOnPeriodEndCoversIt() {
        assertThat(SalaryStructureService.covers(
                        structure(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 30)), PERIOD_START, PERIOD_END))
                .isTrue();
    }

    @Test
    void gapInCoverageMeansNoStructureCoversThePeriod() {
        // Employee has a structure Jan-Aug and another Oct-open; September falls in the gap.
        assertThat(SalaryStructureService.covers(
                        structure(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 8, 31)), PERIOD_START, PERIOD_END))
                .isFalse();
        assertThat(SalaryStructureService.covers(structure(LocalDate.of(2026, 10, 1), null), PERIOD_START, PERIOD_END))
                .isFalse();
    }
}
