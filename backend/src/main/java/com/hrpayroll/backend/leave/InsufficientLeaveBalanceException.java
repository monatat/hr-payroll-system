package com.hrpayroll.backend.leave;

/** Thrown at approval time when the employee's remaining balance is less than the request's daysRequested. */
public class InsufficientLeaveBalanceException extends RuntimeException {

    public InsufficientLeaveBalanceException() {
        super("The employee's remaining leave balance is insufficient for the requested days.");
    }
}
