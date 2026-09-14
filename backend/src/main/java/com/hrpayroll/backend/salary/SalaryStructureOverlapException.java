package com.hrpayroll.backend.salary;

/**
 * Thrown when a proposed salary structure period overlaps an existing one
 * for the same employee (after the one approved succession adjustment has
 * been considered). Mapped to 409 by {@link SalaryExceptionHandler}.
 */
public class SalaryStructureOverlapException extends RuntimeException {

    public SalaryStructureOverlapException() {
        super("The proposed salary structure period overlaps an existing salary structure for this employee.");
    }
}
