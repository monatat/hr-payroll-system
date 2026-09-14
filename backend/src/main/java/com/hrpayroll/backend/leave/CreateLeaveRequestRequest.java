package com.hrpayroll.backend.leave;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * employeeId, daysRequested, status, and every review field are
 * deliberately absent -- employeeId comes only from the authenticated
 * principal (see LeaveRequestController), daysRequested is always
 * server-computed via WorkingDayCalculator (or fixed at 0.5 for HALF_DAY),
 * and a request always starts PENDING with no review fields set.
 * durationType is deliberately nullable rather than {@code @NotNull} -- an
 * omitted value (or an older client predating Phase 7D) is normalized to
 * FULL_DAY in {@link LeaveRequestService}, never rejected as invalid.
 */
public record CreateLeaveRequestRequest(
        @NotNull Long leaveTypeId, @NotNull LocalDate startDate, @NotNull LocalDate endDate,
        @Size(max = 500) String reason, LeaveDurationType durationType) {

    /**
     * Compatibility constructor matching the pre-Phase-7D signature --
     * preserves every existing 4-arg call site without edits. durationType
     * defaults to null here, normalized to FULL_DAY downstream.
     */
    public CreateLeaveRequestRequest(Long leaveTypeId, LocalDate startDate, LocalDate endDate, String reason) {
        this(leaveTypeId, startDate, endDate, reason, null);
    }
}
