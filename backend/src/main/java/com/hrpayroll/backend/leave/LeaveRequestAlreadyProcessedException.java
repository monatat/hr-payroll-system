package com.hrpayroll.backend.leave;

/**
 * Thrown when approve/reject is attempted on a request that is no longer
 * PENDING -- either because it was already processed (checked up front) or
 * because a concurrent approve/reject won the race (the optimistic-lock
 * conflict from the early {@code saveAndFlush} is converted to this same
 * exception, so both cases produce an identical, stable response).
 */
public class LeaveRequestAlreadyProcessedException extends RuntimeException {

    public LeaveRequestAlreadyProcessedException() {
        super("This leave request has already been processed and can no longer be approved or rejected.");
    }
}
