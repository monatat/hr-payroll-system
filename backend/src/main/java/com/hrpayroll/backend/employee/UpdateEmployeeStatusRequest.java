package com.hrpayroll.backend.employee;

import jakarta.validation.constraints.NotNull;

public record UpdateEmployeeStatusRequest(@NotNull EmploymentStatus status) {
}
