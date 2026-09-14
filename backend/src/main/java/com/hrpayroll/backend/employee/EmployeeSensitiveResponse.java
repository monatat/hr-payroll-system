package com.hrpayroll.backend.employee;

public record EmployeeSensitiveResponse(
        Long id,
        String icNumber,
        String bankName,
        String bankAccountNo) {
}
