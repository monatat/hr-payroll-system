package com.hrpayroll.backend.demo;

/**
 * Thrown when the demo dataset cannot be created or verified. The message is fixed and never carries
 * an email, password, hash, SQL, constraint name, or the underlying persistence exception (no cause is
 * attached), so a failed demo startup cannot leak sensitive or internal detail into logs.
 */
public class DemoDataConflictException extends RuntimeException {

    public static final String MESSAGE = "Demo data could not be created: reserved demo records conflict with existing data "
            + "or the demo dataset is incomplete. No demo data was written.";

    public DemoDataConflictException() {
        super(MESSAGE);
    }
}
