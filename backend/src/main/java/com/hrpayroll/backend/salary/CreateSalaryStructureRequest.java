package com.hrpayroll.backend.salary;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * employeeId is deliberately absent -- it comes only from the path, never
 * from the request body (see SalaryStructureController), so a client can
 * never create a structure "for" an employee other than the one the URL
 * names. housingAllowance/transportAllowance are optional here; the service
 * normalizes a missing value to BigDecimal.ZERO, mirroring the DB's own
 * "NOT NULL DEFAULT 0". @Digits(integer = 10, fraction = 2) matches the V5
 * NUMERIC(12,2) columns exactly (10 + 2 = 12 total digits).
 */
public record CreateSalaryStructureRequest(
        @NotNull @DecimalMin(value = "0.00") @Digits(integer = 10, fraction = 2) BigDecimal basePay,
        @DecimalMin(value = "0.00") @Digits(integer = 10, fraction = 2) BigDecimal housingAllowance,
        @DecimalMin(value = "0.00") @Digits(integer = 10, fraction = 2) BigDecimal transportAllowance,
        @NotNull LocalDate effectiveFrom,
        LocalDate effectiveTo) {
}
