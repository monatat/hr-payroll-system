package com.hrpayroll.backend.payroll;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * HR_ADMIN-facing payroll run DTO. The rate snapshots are included deliberately -- they explain
 * the historical calculation policy actually used for this run -- but remain fixed, flat,
 * non-compliant demonstration figures, not real statutory rates (see docs). Never includes audit
 * actor information, stack traces, exception details, or any JPA relationship -- an entity is
 * never serialized directly.
 */
public record PayrollRunResponse(
        Long id,
        Integer periodMonth,
        Integer periodYear,
        LocalDate periodStart,
        LocalDate periodEnd,
        PayrollRunStatus status,
        String failureReason,
        BigDecimal epfRateSnapshot,
        BigDecimal socsoRateSnapshot,
        BigDecimal eisRateSnapshot,
        BigDecimal incomeTaxRateSnapshot,
        Long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static PayrollRunResponse from(PayrollRun run) {
        return new PayrollRunResponse(
                run.getId(),
                run.getPeriodMonth(),
                run.getPeriodYear(),
                run.getPeriodStart(),
                run.getPeriodEnd(),
                run.getStatus(),
                run.getFailureReason(),
                run.getEpfRateSnapshot(),
                run.getSocsoRateSnapshot(),
                run.getEisRateSnapshot(),
                run.getIncomeTaxRateSnapshot(),
                run.getVersion(),
                run.getCreatedAt(),
                run.getUpdatedAt());
    }
}
