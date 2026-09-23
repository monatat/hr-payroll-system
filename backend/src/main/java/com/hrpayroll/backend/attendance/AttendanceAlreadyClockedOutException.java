package com.hrpayroll.backend.attendance;

/**
 * Thrown by clock-out when today's row already has a {@code clockOutAt}, including the losing
 * side of a concurrent clock-out race (the conditional update affected zero rows).
 */
public class AttendanceAlreadyClockedOutException extends RuntimeException {

    public AttendanceAlreadyClockedOutException() {
        super("You have already clocked out today.");
    }
}
