package com.hrpayroll.backend.reports;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HR_ADMIN-only report endpoints (Phase 10B): payroll cost and headcount. Delegates entirely to
 * {@link ReportService}; never touches a repository directly, never decodes a JWT manually, and
 * never creates an audit record -- a report read is not a mutation. Neither endpoint takes an
 * employee/user/department/status/caller-identity parameter: the approved report contracts need
 * only a year (payroll cost) or nothing at all (headcount, which is fixed to current ACTIVE by the
 * repository query itself).
 */
@RestController
@RequestMapping("/api/reports")
@PreAuthorize("hasRole('HR_ADMIN')")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/payroll-cost")
    public PayrollCostReportResponse payrollCost(@RequestParam int year) {
        return reportService.getPayrollCostReport(year);
    }

    @GetMapping("/headcount")
    public HeadcountReportResponse headcount() {
        return reportService.getHeadcountReport();
    }
}
