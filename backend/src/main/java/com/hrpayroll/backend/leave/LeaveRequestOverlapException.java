package com.hrpayroll.backend.leave;

/**
 * Thrown when a proposed leave period overlaps an existing PENDING or
 * APPROVED request for the same employee -- REJECTED requests never
 * trigger this, and the overlap check applies across all leave types (one
 * employee cannot take two kinds of leave over the same dates).
 */
public class LeaveRequestOverlapException extends RuntimeException {

    public LeaveRequestOverlapException() {
        super("The requested leave period overlaps an existing pending or approved leave request for this employee.");
    }
}
