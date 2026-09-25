package com.hrpayroll.backend.reports;

import com.hrpayroll.backend.employee.EmployeeRepository;
import com.hrpayroll.backend.employee.EmploymentStatus;
import com.hrpayroll.backend.payroll.InvalidPayrollPeriodException;
import com.hrpayroll.backend.payroll.PayslipRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only HR_ADMIN report queries (Phase 10B), built entirely on top of the Phase 10A repository
 * foundation -- never duplicates any aggregation in Java. Deliberately has no dependency on
 * AuditService: a report read is not a mutation and creates no audit trail, exactly like
 * {@code PayrollQueryService}.
 */
@Service
@Transactional(readOnly = true)
public class ReportService {

    private final PayslipRepository payslipRepository;
    private final EmployeeRepository employeeRepository;

    public ReportService(PayslipRepository payslipRepository, EmployeeRepository employeeRepository) {
        this.payslipRepository = payslipRepository;
        this.employeeRepository = employeeRepository;
    }

    /**
     * Reuses PayrollQueryService/PayrollPeriod's exact 2000-2100 year range -- the same range
     * already validated for a payroll run's own period, since a payroll-cost report is meaningless
     * outside the range of years a payroll run could ever exist for.
     */
    public PayrollCostReportResponse getPayrollCostReport(int year) {
        if (year < 2000 || year > 2100) {
            throw new InvalidPayrollPeriodException("year must be between 2000 and 2100: was " + year);
        }

        List<MonthlyPayrollCostResponse> monthlyTotals = payslipRepository.findMonthlyGrossPayTotals(year).stream()
                .map(MonthlyPayrollCostResponse::from)
                .toList();
        return new PayrollCostReportResponse(year, payslipRepository.findAnnualGrossPayTotal(year), monthlyTotals);
    }

    public HeadcountReportResponse getHeadcountReport() {
        List<DepartmentHeadcountResponse> departments = employeeRepository.findActiveHeadcountByDepartment().stream()
                .map(DepartmentHeadcountResponse::from)
                .toList();
        return new HeadcountReportResponse(employeeRepository.countByEmploymentStatus(EmploymentStatus.ACTIVE), departments);
    }
}
