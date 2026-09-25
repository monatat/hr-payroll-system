package com.hrpayroll.backend.reports;

/**
 * HR_ADMIN-facing current-ACTIVE-headcount DTO for one department, mapped directly from the Phase
 * 10A repository projection {@link DepartmentHeadcountSummary} -- kept as a separate type so the
 * HTTP response contract never depends on (or leaks) the persistence-layer projection shape
 * directly.
 */
public record DepartmentHeadcountResponse(Long departmentId, String departmentName, long activeEmployeeCount) {

    public static DepartmentHeadcountResponse from(DepartmentHeadcountSummary summary) {
        return new DepartmentHeadcountResponse(summary.departmentId(), summary.departmentName(), summary.activeEmployeeCount());
    }
}
