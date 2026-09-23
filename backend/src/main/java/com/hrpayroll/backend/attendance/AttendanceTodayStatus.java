package com.hrpayroll.backend.attendance;

/** The three explicit states of an employee's attendance for the current business date. */
public enum AttendanceTodayStatus {
    NOT_CLOCKED_IN,
    CLOCKED_IN,
    CLOCKED_OUT
}
