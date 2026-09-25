package com.hrpayroll.backend.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hrpayroll.backend.employee.EmployeeRepository;
import com.hrpayroll.backend.employee.EmploymentStatus;
import com.hrpayroll.backend.payroll.InvalidPayrollPeriodException;
import com.hrpayroll.backend.payroll.PayslipRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure unit tests against mocked repositories -- no Spring context, no real database writes,
 * mirroring {@code BootstrapAdminRunnerTest}'s precedent for this project. Exists specifically to
 * prove Phase 10B behaviors that {@link ReportControllerTest}'s real-PostgreSQL integration tests
 * cannot observe (see that class's Javadoc for the full reasoning): that the annual total and total
 * headcount come from their own dedicated authoritative repository queries rather than being
 * resummed/recounted in Java, and the exact empty-repository-result mapping for headcount (which
 * has no scoping dimension like payroll's {@code year}, so the shared integration database's real
 * ACTIVE employees can never be forced to zero the way an unused year forces payroll cost to zero).
 */
@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    @Mock
    private PayslipRepository payslipRepository;

    @Mock
    private EmployeeRepository employeeRepository;

    private ReportService reportService() {
        return new ReportService(payslipRepository, employeeRepository);
    }

    @Test
    void payrollCostReportUsesTheAnnualTotalRepositoryQueryNeverResummingMonthlyRows() {
        int year = 2050;
        // Deliberately inconsistent with the monthly rows below (which sum to 300.00) -- if the
        // service ever resummed monthlyTotals in Java instead of using this authoritative value,
        // the response would show 300.00, not this value, proving which source was actually used.
        when(payslipRepository.findAnnualGrossPayTotal(year)).thenReturn(new BigDecimal("999.99"));
        when(payslipRepository.findMonthlyGrossPayTotals(year))
                .thenReturn(List.of(
                        new MonthlyPayrollCostSummary(1, new BigDecimal("100.00")),
                        new MonthlyPayrollCostSummary(2, new BigDecimal("200.00"))));

        PayrollCostReportResponse response = reportService().getPayrollCostReport(year);

        assertThat(response.annualTotalGrossPay()).isEqualByComparingTo("999.99");
    }

    @Test
    void payrollCostReportPreservesRepositoryMonthlyOrderingAndMapsEveryField() {
        int year = 2051;
        when(payslipRepository.findAnnualGrossPayTotal(year)).thenReturn(new BigDecimal("300.00"));
        when(payslipRepository.findMonthlyGrossPayTotals(year))
                .thenReturn(List.of(
                        new MonthlyPayrollCostSummary(3, new BigDecimal("300.00")),
                        new MonthlyPayrollCostSummary(7, new BigDecimal("0.00"))));

        PayrollCostReportResponse response = reportService().getPayrollCostReport(year);

        assertThat(response.year()).isEqualTo(year);
        assertThat(response.monthlyTotals()).hasSize(2);
        assertThat(response.monthlyTotals().get(0).periodMonth()).isEqualTo(3);
        assertThat(response.monthlyTotals().get(1).periodMonth()).isEqualTo(7);
    }

    @Test
    void payrollCostReportForAYearWithNoQualifyingPayslipsReturnsZeroAndAnEmptyListNeverNull() {
        int year = 2052;
        when(payslipRepository.findAnnualGrossPayTotal(year)).thenReturn(BigDecimal.ZERO);
        when(payslipRepository.findMonthlyGrossPayTotals(year)).thenReturn(List.of());

        PayrollCostReportResponse response = reportService().getPayrollCostReport(year);

        assertThat(response.annualTotalGrossPay()).isNotNull().isEqualByComparingTo("0");
        assertThat(response.monthlyTotals()).isNotNull().isEmpty();
    }

    @Test
    void belowMinimumYearIsRejectedBeforeTouchingAnyRepository() {
        assertThatThrownBy(() -> reportService().getPayrollCostReport(1999))
                .isInstanceOf(InvalidPayrollPeriodException.class);

        verifyNoInteractions(payslipRepository);
    }

    @Test
    void aboveMaximumYearIsRejectedBeforeTouchingAnyRepository() {
        assertThatThrownBy(() -> reportService().getPayrollCostReport(2101))
                .isInstanceOf(InvalidPayrollPeriodException.class);

        verifyNoInteractions(payslipRepository);
    }

    @Test
    void headcountReportUsesTheTotalActiveCountQueryNeverSummingDepartmentRows() {
        // Deliberately inconsistent with the department rows below (which sum to 7) -- if the
        // service ever summed departments in Java instead of using this authoritative value, the
        // response would show 7, not this value, proving which source was actually used.
        when(employeeRepository.countByEmploymentStatus(EmploymentStatus.ACTIVE)).thenReturn(42L);
        when(employeeRepository.findActiveHeadcountByDepartment())
                .thenReturn(List.of(
                        new DepartmentHeadcountSummary(1L, "Engineering", 4L),
                        new DepartmentHeadcountSummary(2L, "Sales", 3L)));

        HeadcountReportResponse response = reportService().getHeadcountReport();

        assertThat(response.totalActiveEmployees()).isEqualTo(42L);
    }

    @Test
    void headcountReportPreservesRepositoryDepartmentOrderingAndMapsEveryField() {
        when(employeeRepository.countByEmploymentStatus(EmploymentStatus.ACTIVE)).thenReturn(7L);
        when(employeeRepository.findActiveHeadcountByDepartment())
                .thenReturn(List.of(
                        new DepartmentHeadcountSummary(5L, "Alpha", 4L),
                        new DepartmentHeadcountSummary(9L, "Zulu", 3L)));

        HeadcountReportResponse response = reportService().getHeadcountReport();

        assertThat(response.departments()).hasSize(2);
        assertThat(response.departments().get(0).departmentId()).isEqualTo(5L);
        assertThat(response.departments().get(0).departmentName()).isEqualTo("Alpha");
        assertThat(response.departments().get(0).activeEmployeeCount()).isEqualTo(4L);
        assertThat(response.departments().get(1).departmentId()).isEqualTo(9L);
    }

    @Test
    void headcountReportWithNoActiveEmployeesAnywhereReturnsZeroAndAnEmptyListNeverNull() {
        when(employeeRepository.countByEmploymentStatus(EmploymentStatus.ACTIVE)).thenReturn(0L);
        when(employeeRepository.findActiveHeadcountByDepartment()).thenReturn(List.of());

        HeadcountReportResponse response = reportService().getHeadcountReport();

        assertThat(response.totalActiveEmployees()).isZero();
        assertThat(response.departments()).isNotNull().isEmpty();
    }
}
