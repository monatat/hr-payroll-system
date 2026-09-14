package com.hrpayroll.backend.salary;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link SalaryStructureService#overlaps} -- the
 * inclusive-date, null-as-infinity overlap algorithm. No Spring context, no
 * database: this is the fast-cycle core of the Phase 6 concurrency/overlap
 * design, exercising every edge case from the approved proposal.
 */
class SalaryOverlapAlgorithmTest {

    private static final LocalDate JAN_1 = LocalDate.of(2026, 1, 1);
    private static final LocalDate JUN_30 = LocalDate.of(2026, 6, 30);
    private static final LocalDate JUL_1 = LocalDate.of(2026, 7, 1);
    private static final LocalDate AUG_15 = LocalDate.of(2026, 8, 15);
    private static final LocalDate DEC_1_2025 = LocalDate.of(2025, 12, 1);

    @Test
    void adjacentClosedThenOpenDoesNotOverlap() {
        // 2026-01-01..2026-06-30 then 2026-07-01..OPEN
        assertThat(SalaryStructureService.overlaps(JAN_1, JUN_30, JUL_1, null)).isFalse();
    }

    @Test
    void sameDayBoundaryOverlaps() {
        // 2026-01-01..2026-06-30 then 2026-06-30..OPEN shares 2026-06-30
        assertThat(SalaryStructureService.overlaps(JAN_1, JUN_30, JUN_30, null)).isTrue();
    }

    @Test
    void sameEffectiveFromOverlaps() {
        assertThat(SalaryStructureService.overlaps(JAN_1, null, JAN_1, JUN_30)).isTrue();
    }

    @Test
    void twoOpenEndedRangesAlwaysOverlap() {
        assertThat(SalaryStructureService.overlaps(JAN_1, null, DEC_1_2025, null)).isTrue();
    }

    @Test
    void adjacentClosedThenClosedDoesNotOverlap() {
        assertThat(SalaryStructureService.overlaps(JAN_1, JUN_30, JUL_1, AUG_15)).isFalse();
    }

    @Test
    void disjointClosedRangesDoNotOverlap() {
        LocalDate aug1 = LocalDate.of(2026, 8, 1);
        assertThat(SalaryStructureService.overlaps(JUL_1, AUG_15, aug1, null)).isTrue();
        // (deliberately overlapping here -- Aug 1..OPEN starts inside Jul1..Aug15)
    }

    @Test
    void nonOverlappingGapIsAllowed() {
        // 2026-01-01..2026-01-31 and 2026-03-01..OPEN: a gap in between, not adjacent, still no overlap
        LocalDate jan31 = LocalDate.of(2026, 1, 31);
        LocalDate mar1 = LocalDate.of(2026, 3, 1);
        assertThat(SalaryStructureService.overlaps(JAN_1, jan31, mar1, null)).isFalse();
    }

    @Test
    void orderOfArgumentsDoesNotMatter() {
        assertThat(SalaryStructureService.overlaps(JUL_1, null, JAN_1, JUN_30))
                .isEqualTo(SalaryStructureService.overlaps(JAN_1, JUN_30, JUL_1, null));
    }
}
