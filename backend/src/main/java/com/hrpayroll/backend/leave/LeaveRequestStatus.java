package com.hrpayroll.backend.leave;

/**
 * Phase 7 foundation only -- no {@code LeaveRequest} entity/behavior exists
 * yet (Phase 7B). Only {@code PENDING -> APPROVED} and
 * {@code PENDING -> REJECTED} will be valid transitions once implemented.
 */
public enum LeaveRequestStatus {
    PENDING,
    APPROVED,
    REJECTED
}
