package com.hrpayroll.backend.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PayrollRun}'s three lifecycle transitions
 * (PROCESSING -> CALCULATED, PROCESSING -> FAILED, FAILED -> PROCESSING via
 * retry). No Spring/database dependency -- pure entity behavior. There is no
 * COMPLETED transition method on this entity at all (enforced by absence,
 * the same way earlier phases enforce "no generic setter" -- there is
 * nothing to call).
 */
class PayrollRunLifecycleTest {

    private static PayrollRun newProcessingRun() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 1, 31);
        return new PayrollRun(
                1,
                2026,
                start,
                end,
                PayrollRunStatus.PROCESSING,
                new BigDecimal("0.1100"),
                new BigDecimal("0.0050"),
                new BigDecimal("0.0020"),
                new BigDecimal("0.0500"));
    }

    @Test
    void newRunStartsAsProcessing() {
        assertThat(newProcessingRun().getStatus()).isEqualTo(PayrollRunStatus.PROCESSING);
    }

    @Test
    void markCalculatedTransitionsFromProcessingToCalculated() {
        PayrollRun run = newProcessingRun();

        run.markCalculated();

        assertThat(run.getStatus()).isEqualTo(PayrollRunStatus.CALCULATED);
    }

    @Test
    void markCalculatedOnANonProcessingRunIsRejected() {
        PayrollRun run = newProcessingRun();
        run.markCalculated();

        assertThatThrownBy(run::markCalculated).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void markFailedTransitionsFromProcessingToFailedAndStoresTheSafeReason() {
        PayrollRun run = newProcessingRun();

        run.markFailed("No eligible employees for this period.");

        assertThat(run.getStatus()).isEqualTo(PayrollRunStatus.FAILED);
        assertThat(run.getFailureReason()).isEqualTo("No eligible employees for this period.");
    }

    @Test
    void markFailedOnANonProcessingRunIsRejected() {
        PayrollRun run = newProcessingRun();
        run.markCalculated();

        assertThatThrownBy(() -> run.markFailed("some reason")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void markFailedAfterAlreadyFailedIsRejected() {
        PayrollRun run = newProcessingRun();
        run.markFailed("first failure");

        assertThatThrownBy(() -> run.markFailed("second failure")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void prepareForRetryTransitionsFromFailedToProcessing() {
        PayrollRun run = newProcessingRun();
        run.markFailed("No eligible employees for this period.");

        run.prepareForRetry();

        assertThat(run.getStatus()).isEqualTo(PayrollRunStatus.PROCESSING);
    }

    @Test
    void prepareForRetryClearsThePreviousFailureReason() {
        PayrollRun run = newProcessingRun();
        run.markFailed("No eligible employees for this period.");

        run.prepareForRetry();

        assertThat(run.getFailureReason()).isNull();
    }

    @Test
    void prepareForRetryOnAProcessingRunIsRejected() {
        PayrollRun run = newProcessingRun();

        assertThatThrownBy(run::prepareForRetry).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void prepareForRetryOnACalculatedRunIsRejected() {
        PayrollRun run = newProcessingRun();
        run.markCalculated();

        assertThatThrownBy(run::prepareForRetry).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void thereIsNoWayToReachCompletedFromAnyState() {
        // No method named/behaving like "markCompleted" exists anywhere on this entity -- enforced
        // by absence, proven here by reflecting over its own declared methods.
        boolean anyMethodMentionsCompleted = java.util.Arrays.stream(PayrollRun.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().toLowerCase(java.util.Locale.ROOT).contains("complet"));

        assertThat(anyMethodMentionsCompleted).isFalse();
    }
}
