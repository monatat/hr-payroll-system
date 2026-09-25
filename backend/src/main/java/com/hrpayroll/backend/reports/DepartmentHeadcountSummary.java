package com.hrpayroll.backend.reports;

/**
 * One department's current ACTIVE headcount -- see
 * {@code EmployeeRepository.findActiveHeadcountByDepartment}. Represents current state only (no
 * historical/as-of-date meaning): {@code employees.department_id} is a live, mutable FK with no
 * assignment-history table, and {@code employees} has no termination date, so a past-dated
 * headcount cannot be reconstructed accurately from this schema. A department with zero current
 * ACTIVE employees never appears. Deliberately carries no employee identity or salary field.
 */
public record DepartmentHeadcountSummary(Long departmentId, String departmentName, long activeEmployeeCount) {
}
