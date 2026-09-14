package com.hrpayroll.backend.leave;

/** Thrown when a leave period contains no Monday-Friday day (e.g. a weekend-only range). */
public class ZeroWorkingDaysRequestedException extends RuntimeException {

    public ZeroWorkingDaysRequestedException() {
        super("The requested leave period contains no working days (Monday-Friday).");
    }
}
