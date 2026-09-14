package com.hrpayroll.backend.employee;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Deliberately has no employeeCode field -- the employee code is immutable
 * after creation. This DTO also never touches the users table: it only
 * carries Employee-owned fields, so there is no way to accidentally modify
 * a security-sensitive User field (email/password/role/accountStatus)
 * through this endpoint.
 *
 * icNumber/bankName/bankAccountNo are optional here (unlike on create): the
 * general GET responses never echo these sensitive fields back (by design),
 * so a client editing e.g. just the job title has no value to resend for
 * them. The service therefore treats a null/absent value here as "leave
 * unchanged", never as "clear it" -- an update can only set these fields to
 * a new value, never blank them out as a side effect of an unrelated edit.
 */
public record UpdateEmployeeRequest(
        @NotBlank @Size(max = 150) String fullName,
        @Size(max = 20) String icNumber,
        @NotNull Long departmentId,
        @NotBlank @Size(max = 100) String jobTitle,
        @NotNull EmploymentStatus employmentStatus,
        @NotNull LocalDate hireDate,
        @Size(max = 100) String bankName,
        @Size(max = 30) String bankAccountNo,
        @Size(max = 20) String phoneNumber) {
}
