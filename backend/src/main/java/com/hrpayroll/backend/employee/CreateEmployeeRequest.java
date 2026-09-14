package com.hrpayroll.backend.employee;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * icNumber/bankName/bankAccountNo are accepted here as write-only input from
 * the authorized HR_ADMIN creating the record -- they are never echoed back
 * in the response (see EmployeeResponse).
 */
public record CreateEmployeeRequest(
        @NotBlank @Size(max = 30) String employeeCode,
        @NotBlank @Size(max = 150) String fullName,
        @NotBlank @Size(max = 20) String icNumber,
        @NotNull Long departmentId,
        @NotBlank @Size(max = 100) String jobTitle,
        EmploymentStatus employmentStatus,
        @NotNull LocalDate hireDate,
        @Size(max = 100) String bankName,
        @Size(max = 30) String bankAccountNo,
        @Size(max = 20) String phoneNumber) {
}
