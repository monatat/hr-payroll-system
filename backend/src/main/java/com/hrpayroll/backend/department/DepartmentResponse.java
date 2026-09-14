package com.hrpayroll.backend.department;

public record DepartmentResponse(Long id, String name) {

    public static DepartmentResponse from(Department department) {
        return new DepartmentResponse(department.getId(), department.getName());
    }
}
