package com.hrpayroll.backend.employee;

import jakarta.validation.constraints.Size;

/**
 * The ONLY field an EMPLOYEE may self-edit in Phase 5. Structurally cannot
 * carry employeeCode, department, jobTitle, employmentStatus, hireDate, IC
 * number, bank details, or any User/security field.
 */
public record UpdateOwnProfileRequest(@Size(max = 20) String phoneNumber) {
}
