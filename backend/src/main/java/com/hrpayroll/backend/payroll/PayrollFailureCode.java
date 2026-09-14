package com.hrpayroll.backend.payroll;

/** Stable failure codes for a FAILED payroll run -- audited and returned to callers instead of raw exception text. */
public enum PayrollFailureCode {
    NO_ELIGIBLE_EMPLOYEES,
    SALARY_COVERAGE_MISSING,
    SALARY_COVERAGE_AMBIGUOUS,
    PAYROLL_CALCULATION_FAILED
}
