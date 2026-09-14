package com.hrpayroll.backend.department;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateDepartmentRequest(@NotBlank @Size(max = 150) String name) {
}
