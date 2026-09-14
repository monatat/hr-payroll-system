package com.hrpayroll.backend.salary;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Deliberately excludes createdAt/updatedAt -- no Phase 6 UI use case needs
 * them (matches EmployeeResponse's own precedent of mapping but not
 * exposing those columns).
 */
public record SalaryStructureResponse(
        Long id,
        Long employeeId,
        BigDecimal basePay,
        BigDecimal housingAllowance,
        BigDecimal transportAllowance,
        LocalDate effectiveFrom,
        LocalDate effectiveTo) {

    public static SalaryStructureResponse from(SalaryStructure salaryStructure) {
        return new SalaryStructureResponse(
                salaryStructure.getId(),
                salaryStructure.getEmployee().getId(),
                salaryStructure.getBasePay(),
                salaryStructure.getHousingAllowance(),
                salaryStructure.getTransportAllowance(),
                salaryStructure.getEffectiveFrom(),
                salaryStructure.getEffectiveTo());
    }
}
