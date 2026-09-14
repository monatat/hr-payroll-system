package com.hrpayroll.backend.payroll;

import org.springframework.stereotype.Service;

/**
 * Coordinates the payroll create-and-calculate workflow across three separately committed
 * transactions, owned by {@link PayrollRunTransactionService}. Deliberately carries no
 * {@code @Transactional} annotation of its own: every call into the transaction service always
 * starts a brand-new, independently proxied transaction (a different Spring bean, never a
 * self-invoked method), which is exactly what lets the FAILED-recording transaction commit even
 * though the calculation transaction it is reacting to has already rolled back.
 */
@Service
public class PayrollRunService {

    private final PayrollRunTransactionService transactionService;

    public PayrollRunService(PayrollRunTransactionService transactionService) {
        this.transactionService = transactionService;
    }

    public PayrollRun createAndCalculate(Integer periodMonth, Integer periodYear, Long actorUserId) {
        PayrollPeriod period = PayrollPeriod.of(periodMonth, periodYear);
        PayrollRun created = transactionService.createRun(period, actorUserId);
        return calculateOrMarkFailed(created.getId(), actorUserId);
    }

    /**
     * Retries a FAILED run in place (Phase 8B-2B): reuses the same row (a replacement is
     * impossible anyway, since {@code uq_payroll_runs_period} would reject it), transitioning it
     * FAILED -> PROCESSING via the independent {@code prepareRetry} transaction, then reuses the
     * exact same calculation/failure-handling path as {@link #createAndCalculate} -- the payroll
     * algorithm itself is never duplicated.
     */
    public PayrollRun retryAndCalculate(Long payrollRunId, Long actorUserId) {
        PayrollRun prepared = transactionService.prepareRetry(payrollRunId, actorUserId);
        return calculateOrMarkFailed(prepared.getId(), actorUserId);
    }

    private PayrollRun calculateOrMarkFailed(Long payrollRunId, Long actorUserId) {
        try {
            return transactionService.calculate(payrollRunId, actorUserId);
        } catch (PayrollRunFailedException ex) {
            recordFailureWithoutHidingASecondFailure(payrollRunId, ex.getFailureCode(), ex.getSafeMessage(), actorUserId, ex);
            throw ex;
        } catch (RuntimeException ex) {
            // Any other unexpected failure during calculation must still leave the run in a
            // terminal FAILED state rather than stuck in PROCESSING -- use the generic stable code.
            PayrollRunFailedException wrapped = new PayrollRunFailedException(
                    payrollRunId, PayrollFailureCode.PAYROLL_CALCULATION_FAILED, "Unexpected error during payroll calculation.", ex);
            recordFailureWithoutHidingASecondFailure(
                    payrollRunId, wrapped.getFailureCode(), wrapped.getSafeMessage(), actorUserId, wrapped);
            throw wrapped;
        }
    }

    /**
     * If recording the FAILED state itself throws, that failure must never silently replace or
     * hide the original calculation failure being reported to the caller -- it is attached as a
     * suppressed exception instead, so it remains visible (stack trace, logs, test assertions)
     * without changing what gets thrown.
     */
    private void recordFailureWithoutHidingASecondFailure(
            Long payrollRunId, PayrollFailureCode failureCode, String safeReason, Long actorUserId, RuntimeException original) {
        try {
            transactionService.markFailed(payrollRunId, failureCode, safeReason, actorUserId);
        } catch (RuntimeException markFailedException) {
            original.addSuppressed(markFailedException);
        }
    }
}
