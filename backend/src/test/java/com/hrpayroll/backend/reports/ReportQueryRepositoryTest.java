package com.hrpayroll.backend.reports;

import static org.assertj.core.api.Assertions.assertThat;

import com.hrpayroll.backend.employee.EmployeeRepository;
import com.hrpayroll.backend.employee.EmploymentStatus;
import com.hrpayroll.backend.payroll.PayslipRepository;
import com.hrpayroll.backend.support.ReportTestFixtures;
import jakarta.persistence.EntityManager;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Real-PostgreSQL query-layer tests for the Phase 10A report foundation. No service or controller
 * exists yet -- these tests exercise PayslipRepository/EmployeeRepository directly, exactly as a
 * future ReportService would.
 */
@SpringBootTest
class ReportQueryRepositoryTest {

    private static final int YEAR = 2077; // reserved for this test class only
    private static final int OTHER_YEAR = 2078; // reserved, used only for the different-year test

    @Autowired
    private PayslipRepository payslipRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    private long departmentId;
    private final List<Long> createdRunIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ReportTestFixtures.cleanup(jdbcTemplate);
        createdRunIds.clear();
        departmentId = ReportTestFixtures.installDepartment(jdbcTemplate, "MAIN");
    }

    @AfterEach
    void tearDown() {
        ReportTestFixtures.cleanup(jdbcTemplate);
        ReportTestFixtures.cleanupPayrollRuns(jdbcTemplate, createdRunIds);
    }

    private long newRun(int month, int year, String status) {
        long id = ReportTestFixtures.insertPayrollRun(jdbcTemplate, month, year, status);
        createdRunIds.add(id);
        return id;
    }

    private long newEmployee(String suffix, String ic) {
        return ReportTestFixtures.insertEmployee(jdbcTemplate, departmentId, suffix, ic, "ACTIVE");
    }

    // ---------------------------------------------------------------- payroll cost

    @Test
    void monthlyAggregationSumsGrossPayExactlyForMultiplePayslipsInSamePeriod() {
        long run = newRun(1, YEAR, "CALCULATED");
        long empA = newEmployee("A", "770101-01-1001");
        long empB = newEmployee("B", "770101-01-1002");
        ReportTestFixtures.insertPayslip(jdbcTemplate, run, empA, "EMP-PHASE10-A", new BigDecimal("4650.55"));
        ReportTestFixtures.insertPayslip(jdbcTemplate, run, empB, "EMP-PHASE10-B", new BigDecimal("3873.45"));
        entityManager.clear();

        List<MonthlyPayrollCostSummary> result = payslipRepository.findMonthlyGrossPayTotals(YEAR);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).periodMonth()).isEqualTo(1);
        assertThat(result.get(0).totalGrossPay()).isEqualByComparingTo("8524.00");
    }

    @Test
    void multipleMonthsReturnSeparateRows() {
        long runJan = newRun(1, YEAR, "CALCULATED");
        long runFeb = newRun(2, YEAR, "CALCULATED");
        long emp = newEmployee("C", "770101-01-1003");
        ReportTestFixtures.insertPayslip(jdbcTemplate, runJan, emp, "EMP-PHASE10-C", new BigDecimal("1000.00"));
        long emp2 = newEmployee("D", "770101-01-1004");
        ReportTestFixtures.insertPayslip(jdbcTemplate, runFeb, emp2, "EMP-PHASE10-D", new BigDecimal("2000.00"));
        entityManager.clear();

        List<MonthlyPayrollCostSummary> result = payslipRepository.findMonthlyGrossPayTotals(YEAR);

        assertThat(result).hasSize(2);
    }

    @Test
    void resultsAreOrderedByPeriodMonthAscendingEvenWhenFixturesInsertedOutOfOrder() {
        long runMarch = newRun(3, YEAR, "CALCULATED");
        long empMarch = newEmployee("E", "770101-01-1005");
        ReportTestFixtures.insertPayslip(jdbcTemplate, runMarch, empMarch, "EMP-PHASE10-E", new BigDecimal("100.00"));

        long runJan = newRun(1, YEAR, "CALCULATED");
        long empJan = newEmployee("F", "770101-01-1006");
        ReportTestFixtures.insertPayslip(jdbcTemplate, runJan, empJan, "EMP-PHASE10-F", new BigDecimal("200.00"));
        entityManager.clear();

        List<MonthlyPayrollCostSummary> result = payslipRepository.findMonthlyGrossPayTotals(YEAR);

        assertThat(result).extracting(MonthlyPayrollCostSummary::periodMonth).containsExactly(1, 3);
    }

    @Test
    void annualTotalEqualsExactBigDecimalSumAcrossQualifyingMonths() {
        long runJan = newRun(1, YEAR, "CALCULATED");
        long empJan = newEmployee("G", "770101-01-1007");
        ReportTestFixtures.insertPayslip(jdbcTemplate, runJan, empJan, "EMP-PHASE10-G", new BigDecimal("1500.25"));

        long runFeb = newRun(2, YEAR, "COMPLETED");
        long empFeb = newEmployee("H", "770101-01-1008");
        ReportTestFixtures.insertPayslip(jdbcTemplate, runFeb, empFeb, "EMP-PHASE10-H", new BigDecimal("2500.75"));
        entityManager.clear();

        BigDecimal total = payslipRepository.findAnnualGrossPayTotal(YEAR);

        assertThat(total).isEqualByComparingTo("4001.00");
    }

    @Test
    void calculatedRunsContribute() {
        long run = newRun(4, YEAR, "CALCULATED");
        long emp = newEmployee("I", "770101-01-1009");
        ReportTestFixtures.insertPayslip(jdbcTemplate, run, emp, "EMP-PHASE10-I", new BigDecimal("500.00"));
        entityManager.clear();

        assertThat(payslipRepository.findAnnualGrossPayTotal(YEAR)).isEqualByComparingTo("500.00");
    }

    @Test
    void completedRunsContribute() {
        long run = newRun(5, YEAR, "COMPLETED");
        long emp = newEmployee("J", "770101-01-1010");
        ReportTestFixtures.insertPayslip(jdbcTemplate, run, emp, "EMP-PHASE10-J", new BigDecimal("600.00"));
        entityManager.clear();

        assertThat(payslipRepository.findAnnualGrossPayTotal(YEAR)).isEqualByComparingTo("600.00");
    }

    @Test
    void processingRunsDoNotContributeEvenWithAnAnomalousPayslipRow() {
        long run = newRun(6, YEAR, "PROCESSING");
        long emp = newEmployee("K", "770101-01-1011");
        // Deliberately anomalous: the real service never creates a payslip under a PROCESSING run.
        ReportTestFixtures.insertPayslip(jdbcTemplate, run, emp, "EMP-PHASE10-K", new BigDecimal("999999.99"));
        entityManager.clear();

        assertThat(payslipRepository.findAnnualGrossPayTotal(YEAR)).isEqualByComparingTo("0");
        assertThat(payslipRepository.findMonthlyGrossPayTotals(YEAR)).isEmpty();
    }

    @Test
    void failedRunsDoNotContributeEvenWithAnAnomalousPayslipRow() {
        long run = newRun(7, YEAR, "FAILED");
        long emp = newEmployee("L", "770101-01-1012");
        // Deliberately anomalous: the real service never creates a payslip under a FAILED run.
        ReportTestFixtures.insertPayslip(jdbcTemplate, run, emp, "EMP-PHASE10-L", new BigDecimal("999999.99"));
        entityManager.clear();

        assertThat(payslipRepository.findAnnualGrossPayTotal(YEAR)).isEqualByComparingTo("0");
        assertThat(payslipRepository.findMonthlyGrossPayTotals(YEAR)).isEmpty();
    }

    @Test
    void aDifferentYearIsExcluded() {
        long runThisYear = newRun(1, YEAR, "CALCULATED");
        long empThisYear = newEmployee("M", "770101-01-1013");
        ReportTestFixtures.insertPayslip(jdbcTemplate, runThisYear, empThisYear, "EMP-PHASE10-M", new BigDecimal("111.00"));

        long runOtherYear = newRun(1, OTHER_YEAR, "CALCULATED");
        long empOtherYear = newEmployee("N", "770101-01-1014");
        ReportTestFixtures.insertPayslip(jdbcTemplate, runOtherYear, empOtherYear, "EMP-PHASE10-N", new BigDecimal("222.00"));
        entityManager.clear();

        assertThat(payslipRepository.findAnnualGrossPayTotal(YEAR)).isEqualByComparingTo("111.00");
    }

    @Test
    void emptyYearReturnsNoMonthlyRowsAndZeroAnnualTotalNeverNull() {
        int emptyYear = 2079;

        List<MonthlyPayrollCostSummary> monthly = payslipRepository.findMonthlyGrossPayTotals(emptyYear);
        BigDecimal annual = payslipRepository.findAnnualGrossPayTotal(emptyYear);

        assertThat(monthly).isEmpty();
        assertThat(annual).isNotNull();
        assertThat(annual).isEqualByComparingTo("0");
    }

    @Test
    void bigDecimalPrecisionIsExactIncludingDecimalCents() {
        long run = newRun(8, YEAR, "CALCULATED");
        long emp = newEmployee("O", "770101-01-1015");
        ReportTestFixtures.insertPayslip(jdbcTemplate, run, emp, "EMP-PHASE10-O", new BigDecimal("0.01"));
        long emp2 = newEmployee("P", "770101-01-1016");
        ReportTestFixtures.insertPayslip(jdbcTemplate, run, emp2, "EMP-PHASE10-P", new BigDecimal("0.02"));
        entityManager.clear();

        assertThat(payslipRepository.findAnnualGrossPayTotal(YEAR)).isEqualByComparingTo("0.03");
    }

    @Test
    void noBasePayOrNetPayValueIsAccidentallyUsedInPlaceOfGrossPay() {
        long run = newRun(9, YEAR, "CALCULATED");
        long emp = newEmployee("Q", "770101-01-1017");
        ReportTestFixtures.insertPayslipWithDistinctBasePayAndNetPay(
                jdbcTemplate, run, emp, "EMP-PHASE10-Q", new BigDecimal("1.00"), new BigDecimal("2.00"), new BigDecimal("3.00"));
        entityManager.clear();

        assertThat(payslipRepository.findAnnualGrossPayTotal(YEAR)).isEqualByComparingTo("2.00");
    }

    @Test
    void multipleEmployeesInOneRunAreSummedWithoutDoubleCountingAnyOne() {
        long run = newRun(10, YEAR, "CALCULATED");
        long empA = newEmployee("R", "770101-01-1018");
        long empB = newEmployee("S", "770101-01-1019");
        ReportTestFixtures.insertPayslip(jdbcTemplate, run, empA, "EMP-PHASE10-R", new BigDecimal("1234.56"));
        ReportTestFixtures.insertPayslip(jdbcTemplate, run, empB, "EMP-PHASE10-S", new BigDecimal("7654.32"));
        entityManager.clear();

        assertThat(payslipRepository.findAnnualGrossPayTotal(YEAR)).isEqualByComparingTo("8888.88");
    }

    // ---------------------------------------------------------------- headcount

    @Test
    void currentActiveTotalIncludesActiveEmployees() {
        newEmployee("T", "770101-01-1020");

        long before = employeeRepository.countByEmploymentStatus(EmploymentStatus.ACTIVE);
        ReportTestFixtures.insertEmployee(jdbcTemplate, departmentId, "T2", "770101-01-1021", "ACTIVE");

        assertThat(employeeRepository.countByEmploymentStatus(EmploymentStatus.ACTIVE)).isEqualTo(before + 1);
    }

    @Test
    void inactiveEmployeesAreExcludedFromActiveCount() {
        long before = employeeRepository.countByEmploymentStatus(EmploymentStatus.ACTIVE);
        ReportTestFixtures.insertEmployee(jdbcTemplate, departmentId, "U", "770101-01-1022", "INACTIVE");

        assertThat(employeeRepository.countByEmploymentStatus(EmploymentStatus.ACTIVE)).isEqualTo(before);
    }

    @Test
    void activeEmployeesAreGroupedUnderTheirCurrentDepartment() {
        newEmployee("V", "770101-01-1023");

        List<DepartmentHeadcountSummary> result = employeeRepository.findActiveHeadcountByDepartment();

        assertThat(result).anySatisfy(row -> {
            assertThat(row.departmentId()).isEqualTo(departmentId);
            assertThat(row.activeEmployeeCount()).isGreaterThanOrEqualTo(1);
        });
    }

    @Test
    void multipleActiveEmployeesInOneDepartmentAggregateCorrectly() {
        long dept = ReportTestFixtures.installDepartment(jdbcTemplate, "AGG");
        ReportTestFixtures.insertEmployee(jdbcTemplate, dept, "W1", "770101-01-1024", "ACTIVE");
        ReportTestFixtures.insertEmployee(jdbcTemplate, dept, "W2", "770101-01-1025", "ACTIVE");
        ReportTestFixtures.insertEmployee(jdbcTemplate, dept, "W3", "770101-01-1026", "ACTIVE");

        List<DepartmentHeadcountSummary> result = employeeRepository.findActiveHeadcountByDepartment();

        DepartmentHeadcountSummary row =
                result.stream().filter(r -> r.departmentId().equals(dept)).findFirst().orElseThrow();
        assertThat(row.activeEmployeeCount()).isEqualTo(3);
    }

    @Test
    void multipleDepartmentsProduceSeparateRows() {
        long deptX = ReportTestFixtures.installDepartment(jdbcTemplate, "MULTI-X");
        long deptY = ReportTestFixtures.installDepartment(jdbcTemplate, "MULTI-Y");
        ReportTestFixtures.insertEmployee(jdbcTemplate, deptX, "X1", "770101-01-1027", "ACTIVE");
        ReportTestFixtures.insertEmployee(jdbcTemplate, deptY, "Y1", "770101-01-1028", "ACTIVE");

        List<DepartmentHeadcountSummary> result = employeeRepository.findActiveHeadcountByDepartment();

        assertThat(result.stream().map(DepartmentHeadcountSummary::departmentId))
                .contains(deptX, deptY);
    }

    @Test
    void departmentRowsAreOrderedByNameThenId() {
        long deptZ = ReportTestFixtures.installDepartment(jdbcTemplate, "ZZZ-ORDER");
        long deptA = ReportTestFixtures.installDepartment(jdbcTemplate, "AAA-ORDER");
        ReportTestFixtures.insertEmployee(jdbcTemplate, deptZ, "Z1", "770101-01-1029", "ACTIVE");
        ReportTestFixtures.insertEmployee(jdbcTemplate, deptA, "A1", "770101-01-1030", "ACTIVE");

        List<DepartmentHeadcountSummary> result = employeeRepository.findActiveHeadcountByDepartment();
        List<Long> ids = result.stream().map(DepartmentHeadcountSummary::departmentId).toList();

        assertThat(ids.indexOf(deptA)).isLessThan(ids.indexOf(deptZ));
    }

    @Test
    void departmentContainingOnlyInactiveEmployeesDoesNotAppear() {
        long deptInactiveOnly = ReportTestFixtures.installDepartment(jdbcTemplate, "INACTIVE-ONLY");
        ReportTestFixtures.insertEmployee(jdbcTemplate, deptInactiveOnly, "IN1", "770101-01-1031", "INACTIVE");

        List<DepartmentHeadcountSummary> result = employeeRepository.findActiveHeadcountByDepartment();

        assertThat(result.stream().map(DepartmentHeadcountSummary::departmentId)).doesNotContain(deptInactiveOnly);
    }

    @Test
    void totalActiveCountEqualsTheDatabaseDefinedActiveCount() {
        long dept = ReportTestFixtures.installDepartment(jdbcTemplate, "CROSSCHECK");
        ReportTestFixtures.insertEmployee(jdbcTemplate, dept, "CC1", "770101-01-1032", "ACTIVE");
        ReportTestFixtures.insertEmployee(jdbcTemplate, dept, "CC2", "770101-01-1033", "ACTIVE");
        ReportTestFixtures.insertEmployee(jdbcTemplate, dept, "CC3", "770101-01-1034", "INACTIVE");

        Long databaseCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM employees WHERE employment_status = 'ACTIVE' AND employee_code LIKE 'EMP-PHASE10-CC%'",
                Long.class);
        long repositoryDeptSum = employeeRepository.findActiveHeadcountByDepartment().stream()
                .filter(r -> r.departmentId().equals(dept))
                .mapToLong(DepartmentHeadcountSummary::activeEmployeeCount)
                .sum();

        assertThat(repositoryDeptSum).isEqualTo(databaseCount);
    }

    /**
     * Neither projection exposes employee identity or an individual employee's compensation --
     * MonthlyPayrollCostSummary's only monetary field is the approved aggregate
     * {@code totalGrossPay} (never a per-employee basePay/grossPay/netPay), and
     * DepartmentHeadcountSummary carries no monetary field at all, only department identity and an
     * aggregate ACTIVE count. The exact record-component lists below are the authoritative check;
     * the substring checks are a secondary guard against a per-employee field slipping in under a
     * different name.
     */
    @Test
    void neitherProjectionExposesEmployeeIdentityOrIndividualCompensationFields() {
        List<String> monthlyFields = Arrays.stream(MonthlyPayrollCostSummary.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        List<String> headcountFields = Arrays.stream(DepartmentHeadcountSummary.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(monthlyFields).containsExactlyInAnyOrder("periodMonth", "totalGrossPay");
        assertThat(headcountFields).containsExactlyInAnyOrder("departmentId", "departmentName", "activeEmployeeCount");
        assertThat(monthlyFields).noneMatch(f -> f.toLowerCase().contains("employee") || f.toLowerCase().contains("salary"));
        assertThat(headcountFields).noneMatch(f -> f.toLowerCase().contains("salary"));
    }
}
