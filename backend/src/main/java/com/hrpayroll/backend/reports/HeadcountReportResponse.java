package com.hrpayroll.backend.reports;

import java.util.List;

/**
 * HR_ADMIN-facing current-ACTIVE-headcount report: an authoritative total (from
 * {@code EmployeeRepository.countByEmploymentStatus} -- never summed here from department rows)
 * plus the current department breakdown (from {@code findActiveHeadcountByDepartment}), already
 * ordered by department name then id. No employee-level data, per the approved Phase 10 report
 * contract. {@code departments} is defensively copied into an immutable snapshot so a caller can
 * never mutate a cached response.
 */
public record HeadcountReportResponse(long totalActiveEmployees, List<DepartmentHeadcountResponse> departments) {

    public HeadcountReportResponse {
        departments = List.copyOf(departments);
    }
}
