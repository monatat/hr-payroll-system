package com.hrpayroll.backend.leave;

/** Thrown at approval time when no leave_balances row exists for the request's employee/leaveType/year. */
public class LeaveBalanceNotFoundException extends RuntimeException {

    public LeaveBalanceNotFoundException() {
        super("No leave balance exists for this employee, leave type, and year.");
    }
}
