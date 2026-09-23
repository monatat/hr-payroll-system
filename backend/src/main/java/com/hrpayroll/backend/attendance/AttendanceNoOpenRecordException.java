package com.hrpayroll.backend.attendance;

/** Thrown by clock-out when no attendance row exists for the employee's current business date. */
public class AttendanceNoOpenRecordException extends RuntimeException {

    public AttendanceNoOpenRecordException() {
        super("You have not clocked in today.");
    }
}
