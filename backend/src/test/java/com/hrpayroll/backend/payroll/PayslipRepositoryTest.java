package com.hrpayroll.backend.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.hrpayroll.backend.employee.Employee;
import com.hrpayroll.backend.employee.EmployeeRepository;
import com.hrpayroll.backend.support.PayrollTestFixtures;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Real-PostgreSQL JPA mapping/repository tests for {@link Payslip}. No
 * service/orchestration/PDF layer exists yet -- payslips are constructed
 * directly here exactly as a future PayrollRunService would build them from
 * a {@link PayrollCalculationResult} and employee/department snapshot data.
 * Payroll runs are saved directly via the repository, bypassing the audited
 * PayrollRunService, so each saved run's id is tracked explicitly and
 * passed to cleanup -- never cleaned up by period_year alone (see
 * PayrollTestFixturesCleanupScopeTest).
 */
@SpringBootTest
class PayslipRepositoryTest {

    @Autowired
    private PayslipRepository payslipRepository;

    @Autowired
    private PayrollRunRepository payrollRunRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long departmentId;
    private final List<Long> createdRunIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        createdRunIds.clear();
        PayrollTestFixtures.cleanup(jdbcTemplate);
        departmentId = PayrollTestFixtures.installDepartment(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        PayrollTestFixtures.cleanup(jdbcTemplate, createdRunIds);
    }

    private PayrollRun newSavedRun(int periodMonth) {
        LocalDate periodStart = LocalDate.of(PayrollTestFixtures.TEST_PERIOD_YEAR, periodMonth, 1);
        LocalDate periodEnd = periodStart.withDayOfMonth(periodStart.lengthOfMonth());
        PayrollRun saved = payrollRunRepository.saveAndFlush(new PayrollRun(
                periodMonth,
                PayrollTestFixtures.TEST_PERIOD_YEAR,
                periodStart,
                periodEnd,
                PayrollRunStatus.PROCESSING,
                new BigDecimal("0.1100"),
                new BigDecimal("0.0050"),
                new BigDecimal("0.0020"),
                new BigDecimal("0.0500")));
        createdRunIds.add(saved.getId());
        return saved;
    }

    private Payslip newPayslip(PayrollRun run, Employee employee) {
        return new Payslip(
                run,
                employee,
                employee.getEmployeeCode(),
                employee.getFullName(),
                "Phase8 Test Department",
                employee.getJobTitle(),
                new BigDecimal("5000.00"),
                new BigDecimal("500.00"),
                new BigDecimal("200.00"),
                new BigDecimal("5700.00"),
                new BigDecimal("627.00"),
                new BigDecimal("28.50"),
                new BigDecimal("11.40"),
                new BigDecimal("285.00"),
                new BigDecimal("951.90"),
                new BigDecimal("4748.10"),
                null,
                PdfStatus.PENDING);
    }

    @Test
    void findWithDetailsByIdFetchesPayrollRunAndEmployeeTogether() {
        long employeeId = PayrollTestFixtures.insertEmployee(jdbcTemplate, departmentId, "A", "770101-01-0001");
        Employee employee = employeeRepository.findById(employeeId).orElseThrow();
        PayrollRun run = newSavedRun(7);
        Payslip saved = payslipRepository.saveAndFlush(newPayslip(run, employee));

        Optional<Payslip> found = payslipRepository.findWithDetailsById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getPayrollRun().getId()).isEqualTo(run.getId());
        assertThat(found.get().getEmployee().getId()).isEqualTo(employeeId);
        assertThat(found.get().getNetPay()).isEqualByComparingTo("4748.10");
        assertThat(found.get().getPdfStatus()).isEqualTo(PdfStatus.PENDING);
    }

    @Test
    void paginatedLookupByPayrollRunIdReturnsOnlyThatRunsPayslips() {
        long employeeAId = PayrollTestFixtures.insertEmployee(jdbcTemplate, departmentId, "B", "770101-01-0002");
        long employeeBId = PayrollTestFixtures.insertEmployee(jdbcTemplate, departmentId, "C", "770101-01-0003");
        Employee employeeA = employeeRepository.findById(employeeAId).orElseThrow();
        Employee employeeB = employeeRepository.findById(employeeBId).orElseThrow();

        PayrollRun runOne = newSavedRun(8);
        PayrollRun runTwo = newSavedRun(9);
        payslipRepository.saveAndFlush(newPayslip(runOne, employeeA));
        payslipRepository.saveAndFlush(newPayslip(runTwo, employeeB));

        Pageable pageable = PageRequest.of(0, 10);
        var page = payslipRepository.findByPayrollRunId(runOne.getId(), pageable);

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getEmployee().getId()).isEqualTo(employeeAId);
    }

    @Test
    void paginatedLookupByEmployeeIdReturnsOnlyThatEmployeesPayslips() {
        long employeeAId = PayrollTestFixtures.insertEmployee(jdbcTemplate, departmentId, "D", "770101-01-0004");
        long employeeBId = PayrollTestFixtures.insertEmployee(jdbcTemplate, departmentId, "E", "770101-01-0005");
        Employee employeeA = employeeRepository.findById(employeeAId).orElseThrow();
        Employee employeeB = employeeRepository.findById(employeeBId).orElseThrow();

        PayrollRun run = newSavedRun(10);
        payslipRepository.saveAndFlush(newPayslip(run, employeeA));
        payslipRepository.saveAndFlush(newPayslip(run, employeeB));

        Pageable pageable = PageRequest.of(0, 10);
        var page = payslipRepository.findByEmployeeId(employeeAId, pageable);

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getEmployee().getId()).isEqualTo(employeeAId);
    }
}
