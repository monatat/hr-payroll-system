package com.hrpayroll.backend.employee;

import java.time.LocalDate;

/**
 * Shared, non-sensitive employee view used by every Phase 5 endpoint
 * (list/detail/create/update/me). Deliberately excludes icNumber, bankName,
 * and bankAccountNo -- those are readable only via the Phase 4
 * HR_ADMIN-only sensitive endpoint.
 */
public record EmployeeResponse(
        Long id,
        String employeeCode,
        String fullName,
        Long departmentId,
        String departmentName,
        String jobTitle,
        EmploymentStatus employmentStatus,
        LocalDate hireDate,
        String phoneNumber) {

    public static EmployeeResponse from(Employee employee) {
        return new EmployeeResponse(
                employee.getId(),
                employee.getEmployeeCode(),
                employee.getFullName(),
                employee.getDepartment().getId(),
                employee.getDepartment().getName(),
                employee.getJobTitle(),
                employee.getEmploymentStatus(),
                employee.getHireDate(),
                employee.getPhoneNumber());
    }
}
