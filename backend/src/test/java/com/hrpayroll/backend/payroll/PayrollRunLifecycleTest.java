package com.hrpayroll.backend.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PayrollRun}'s four lifecycle transitions
 * (PROCESSING -> CALCULATED, PROCESSING -> FAILED, FAILED -> PROCESSING via
 * retry, CALCULATED -> COMPLETED). No Spring/database dependency -- pure
 * entity behavior. {@code markCompleted()} is the only declared method
 * capable of reaching COMPLETED (Phase 8C-2A) -- the future 8C-2B
 * orchestration service is responsible for verifying that every payslip in
 * the run is GENERATED before ever calling it; the entity itself cannot
 * query other rows and enforces only its own single-precondition invariant
 * (must currently be CALCULATED), the same defensive-invariant style as
 * every other lifecycle method here.
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
    void markCompletedTransitionsFromCalculatedToCompleted() {
        PayrollRun run = newProcessingRun();
        run.markCalculated();

        run.markCompleted();

        assertThat(run.getStatus()).isEqualTo(PayrollRunStatus.COMPLETED);
    }

    @Test
    void markCompletedOnAProcessingRunIsRejected() {
        PayrollRun run = newProcessingRun();

        assertThatThrownBy(run::markCompleted).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void markCompletedOnAFailedRunIsRejected() {
        PayrollRun run = newProcessingRun();
        run.markFailed("some reason");

        assertThatThrownBy(run::markCompleted).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void markCompletedOnAnAlreadyCompletedRunIsRejected() {
        PayrollRun run = newProcessingRun();
        run.markCalculated();
        run.markCompleted();

        assertThatThrownBy(run::markCompleted).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void markCompletedIsTheOnlyDeclaredMethodCapableOfReachingCompleted() {
        // Exactly one method on this entity may mention "complet" in its name -- markCompleted
        // itself. This replaces the earlier Phase 8A-2/8B-1 "no method reaches COMPLETED" test,
        // now that markCompleted intentionally exists (Phase 8C-2A).
        java.util.List<String> methodsMentioningCompleted = java.util.Arrays.stream(PayrollRun.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName)
                .filter(name -> name.toLowerCase(java.util.Locale.ROOT).contains("complet"))
                .toList();

        assertThat(methodsMentioningCompleted).containsExactly("markCompleted");
    }

    @Test
    void noGenericSetStatusMethodExists() {
        boolean anySetStatusMethod = java.util.Arrays.stream(PayrollRun.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals("setStatus"));

        assertThat(anySetStatusMethod).isFalse();
    }
}
