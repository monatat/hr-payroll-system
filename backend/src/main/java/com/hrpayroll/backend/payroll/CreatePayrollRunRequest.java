package com.hrpayroll.backend.payroll;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * The only client-suppliable payroll-run input: month and year. Structurally cannot carry a
 * status, rate, period date, actor id, or any monetary/payslip value -- those are either
 * server-derived ({@link PayrollPeriod#of}, {@link PayrollRateSnapshot#demoDefaults}) or come only
 * from the authenticated principal ({@code AuthenticatedUser.userId()}), never from this body.
 */
public record CreatePayrollRunRequest(
        @NotNull @Min(1) @Max(12) Integer month, @NotNull @Min(2000) @Max(2100) Integer year) {
}
