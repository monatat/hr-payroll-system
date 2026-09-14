package com.hrpayroll.backend.leave;

/** Thrown for reversed dates (endDate before startDate) or dates spanning more than one calendar year. */
public class InvalidLeaveDatesException extends RuntimeException {

    public InvalidLeaveDatesException(String message) {
        super(message);
    }
}
