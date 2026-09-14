package com.hrpayroll.backend.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hrpayroll.backend.support.PayrollTestFixtures;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Real-PostgreSQL integration tests for {@link PayrollRunService}'s
 * create-and-calculate workflow (Phase 8B-1). Uses period year 2000
 * exclusively -- verified read-only beforehand that no existing employee in
 * the shared database has hire_date on/before any 2000 period_start, so
 * every candidate found for these tests is one this class itself inserted.
 */
@SpringBootTest
class PayrollRunServiceTest {

    @Autowired
    private PayrollRunService payrollRunService;

    @Autowired
    private PayrollRunRepository payrollRunRepository;

    @Autowired
    private PayslipRepository payslipRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private static final int YEAR = PayrollTestFixtures.CANDIDATE_TEST_PERIOD_YEAR;
    private static final LocalDate BEFORE_PERIOD = LocalDate.of(1999, 1, 1);

    private long departmentId;
    private long adminUserId;
    private final List<Long> extraRunIds = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        extraRunIds.clear();
        PayrollTestFixtures.cleanup(jdbcTemplate);
        departmentId = PayrollTestFixtures.installDepartment(jdbcTemplate);
        adminUserId = PayrollTestFixtures.installAdminUser(jdbcTemplate, passwordEncoder);
    }

    @AfterEach
    void tearDown() {
        PayrollTestFixtures.cleanup(jdbcTemplate, extraRunIds);
    }

    private long insertCandidate(String codeSuffix, String ic) {
        return PayrollTestFixtures.insertEmployee(jdbcTemplate, departmentId, codeSuffix, ic, BEFORE_PERIOD, "ACTIVE");
    }

    private void coverFullMonth(long employeeId, int month, BigDecimal basePay, BigDecimal housing, BigDecimal transport) {
        LocalDate periodStart = LocalDate.of(YEAR, month, 1);
        PayrollTestFixtures.insertSalaryStructure(jdbcTemplate, employeeId, basePay, housing, transport, periodStart, null);
    }

    // ---- Group B: successful all-or-nothing calculation ----

    @Test
    void onlyEligibleActiveEmployeesHiredOnOrBeforePeriodStartBecomeCandidates() {
        int month = 1;
        LocalDate periodStart = LocalDate.of(YEAR, month, 1);

        long eligible = insertCandidate("ELIG", "000001010001");
        coverFullMonth(eligible, month, new BigDecimal("1000.00"), BigDecimal.ZERO, BigDecimal.ZERO);

        long inactive = PayrollTestFixtures.insertEmployee(jdbcTemplate, departmentId, "INACTIVE", "000001010002", BEFORE_PERIOD, "INACTIVE");
        coverFullMonth(inactive, month, new BigDecimal("1000.00"), BigDecimal.ZERO, BigDecimal.ZERO);

        long hiredDuringMonth = PayrollTestFixtures.insertEmployee(
                jdbcTemplate, departmentId, "MIDHIRE", "000001010003", periodStart.plusDays(5), "ACTIVE");
        coverFullMonth(hiredDuringMonth, month, new BigDecimal("1000.00"), BigDecimal.ZERO, BigDecimal.ZERO);

        long hiredAfterMonth = PayrollTestFixtures.insertEmployee(
                jdbcTemplate, departmentId, "LATEHIRE", "000001010004", periodStart.plusMonths(1), "ACTIVE");
        coverFullMonth(hiredAfterMonth, month, new BigDecimal("1000.00"), BigDecimal.ZERO, BigDecimal.ZERO);

        PayrollRun run = payrollRunService.createAndCalculate(month, YEAR, adminUserId);

        List<Long> payslipEmployeeIds =
                payslipRepository.findByPayrollRunId(run.getId(), org.springframework.data.domain.Pageable.unpaged())
                        .stream()
                        .map(p -> p.getEmployee().getId())
                        .toList();
        assertThat(payslipEmployeeIds).containsExactly(eligible);
    }

    @Test
    void candidatesAreProcessedInDeterministicEmployeeCodeThenIdOrder() {
        int month = 2;
        long zCandidate = insertCandidate("Z-LAST", "000002020001");
        long aCandidate = insertCandidate("A-FIRST", "000002020002");
        long mCandidate = insertCandidate("M-MIDDLE", "000002020003");
        for (long id : List.of(zCandidate, aCandidate, mCandidate)) {
            coverFullMonth(id, month, new BigDecimal("1000.00"), BigDecimal.ZERO, BigDecimal.ZERO);
        }

        PayrollRun run = payrollRunService.createAndCalculate(month, YEAR, adminUserId);

        List<String> orderedCodes = jdbcTemplate.queryForList(
                "SELECT employee_code_snapshot FROM payslips WHERE payroll_run_id = ? ORDER BY id",
                String.class,
                run.getId());
        assertThat(orderedCodes)
                .containsExactly(
                        PayrollTestFixtures.EMPLOYEE_CODE_PREFIX + "A-FIRST",
                        PayrollTestFixtures.EMPLOYEE_CODE_PREFIX + "M-MIDDLE",
                        PayrollTestFixtures.EMPLOYEE_CODE_PREFIX + "Z-LAST");
    }

    @Test
    void openEndedSalaryStructureCoveringPeriodIsAccepted() {
        int month = 3;
        long employeeId = insertCandidate("OPEN", "000003030001");
        coverFullMonth(employeeId, month, new BigDecimal("2000.00"), BigDecimal.ZERO, BigDecimal.ZERO);

        PayrollRun run = payrollRunService.createAndCalculate(month, YEAR, adminUserId);

        assertThat(run.getStatus()).isEqualTo(PayrollRunStatus.CALCULATED);
    }

    @Test
    void closedSalaryStructureCoveringExactPeriodBoundariesIsAccepted() {
        int month = 4;
        LocalDate periodStart = LocalDate.of(YEAR, month, 1);
        LocalDate periodEnd = periodStart.withDayOfMonth(periodStart.lengthOfMonth());
        long employeeId = insertCandidate("EXACT", "000004040001");
        PayrollTestFixtures.insertSalaryStructure(
                jdbcTemplate, employeeId, new BigDecimal("2500.00"), BigDecimal.ZERO, BigDecimal.ZERO, periodStart, periodEnd);

        PayrollRun run = payrollRunService.createAndCalculate(month, YEAR, adminUserId);

        assertThat(run.getStatus()).isEqualTo(PayrollRunStatus.CALCULATED);
        assertThat(payslipRepository.findByPayrollRunId(run.getId(), org.springframework.data.domain.Pageable.unpaged())
                        .getTotalElements())
                .isEqualTo(1);
    }

    @Test
    void calculatedMonetaryValuesMatchPayrollCalculatorExactly() {
        int month = 5;
        long employeeId = insertCandidate("MONEY", "000005050001");
        coverFullMonth(employeeId, month, new BigDecimal("5000.00"), new BigDecimal("500.00"), new BigDecimal("200.00"));

        PayrollRun run = payrollRunService.createAndCalculate(month, YEAR, adminUserId);

        Payslip payslip = payslipRepository.findByEmployeeId(employeeId, org.springframework.data.domain.Pageable.unpaged())
                .getContent()
                .get(0);
        PayrollRateSnapshot rates = new PayrollRateSnapshot(
                run.getEpfRateSnapshot(), run.getSocsoRateSnapshot(), run.getEisRateSnapshot(), run.getIncomeTaxRateSnapshot());
        PayrollCalculationResult expected = PayrollCalculator.calculate(
                new BigDecimal("5000.00"), new BigDecimal("500.00"), new BigDecimal("200.00"), rates);

        assertThat(payslip.getBasePay()).isEqualByComparingTo(expected.basePay());
        assertThat(payslip.getGrossPay()).isEqualByComparingTo(expected.grossPay());
        assertThat(payslip.getEpfDeduction()).isEqualByComparingTo(expected.epfDeduction());
        assertThat(payslip.getSocsoDeduction()).isEqualByComparingTo(expected.socsoDeduction());
        assertThat(payslip.getEisDeduction()).isEqualByComparingTo(expected.eisDeduction());
        assertThat(payslip.getIncomeTax()).isEqualByComparingTo(expected.incomeTax());
        assertThat(payslip.getTotalDeductions()).isEqualByComparingTo(expected.totalDeductions());
        assertThat(payslip.getNetPay()).isEqualByComparingTo(expected.netPay());
    }

    @Test
    void payslipSnapshotsMatchEmployeeDepartmentJobTitleAtCalculationTime() {
        int month = 6;
        long employeeId = insertCandidate("SNAP", "000006060001");
        coverFullMonth(employeeId, month, new BigDecimal("1000.00"), BigDecimal.ZERO, BigDecimal.ZERO);

        PayrollRun run = payrollRunService.createAndCalculate(month, YEAR, adminUserId);

        Payslip payslip = payslipRepository.findByEmployeeId(employeeId, org.springframework.data.domain.Pageable.unpaged())
                .getContent()
                .get(0);
        assertThat(payslip.getEmployeeCodeSnapshot()).isEqualTo(PayrollTestFixtures.EMPLOYEE_CODE_PREFIX + "SNAP");
        assertThat(payslip.getEmployeeNameSnapshot()).isEqualTo("Phase8 Test Employee");
        assertThat(payslip.getDepartmentNameSnapshot()).isEqualTo(PayrollTestFixtures.DEPARTMENT_NAME);
        assertThat(payslip.getJobTitleSnapshot()).isEqualTo("Engineer");
        assertThat(run.getStatus()).isEqualTo(PayrollRunStatus.CALCULATED);
    }

    @Test
    void payslipPdfStatusIsPendingWithNullPath() {
        int month = 7;
        long employeeId = insertCandidate("PDF", "000007070001");
        coverFullMonth(employeeId, month, new BigDecimal("1000.00"), BigDecimal.ZERO, BigDecimal.ZERO);

        PayrollRun run = payrollRunService.createAndCalculate(month, YEAR, adminUserId);

        Payslip payslip = payslipRepository.findByEmployeeId(employeeId, org.springframework.data.domain.Pageable.unpaged())
                .getContent()
                .get(0);
        assertThat(payslip.getPdfStatus()).isEqualTo(PdfStatus.PENDING);
        assertThat(payslip.getPdfPath()).isNull();
        assertThat(run.getId()).isNotNull(); // sanity: this payslip really belongs to a real run
    }

    @Test
    void successfulRunEndsInCalculatedStatusWithAdvancedVersion() {
        int month = 8;
        long employeeId = insertCandidate("VER", "000008080001");
        coverFullMonth(employeeId, month, new BigDecimal("1000.00"), BigDecimal.ZERO, BigDecimal.ZERO);

        PayrollRun run = payrollRunService.createAndCalculate(month, YEAR, adminUserId);

        PayrollRun reloaded = payrollRunRepository.findById(run.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PayrollRunStatus.CALCULATED);
        // version 0 at creation (transaction 1), advanced at least once by the CALCULATED update (transaction 2).
        assertThat(reloaded.getVersion()).isGreaterThan(0L);
    }

    @Test
    void exactlyOneStartedAndOneCalculatedAuditExistWithNoMonetaryOrRateValues() {
        int month = 9;
        long employeeId = insertCandidate("AUDIT", "000009090001");
        BigDecimal secretBasePay = new BigDecimal("123456.78");
        coverFullMonth(employeeId, month, secretBasePay, BigDecimal.ZERO, BigDecimal.ZERO);

        PayrollRun run = payrollRunService.createAndCalculate(month, YEAR, adminUserId);

        Integer startedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE entity_name = 'PAYROLL_RUN' AND entity_id = ? AND action = 'PAYROLL_RUN_STARTED'",
                Integer.class,
                run.getId());
        Integer calculatedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE entity_name = 'PAYROLL_RUN' AND entity_id = ? AND action = 'PAYROLL_RUN_CALCULATED'",
                Integer.class,
                run.getId());
        assertThat(startedCount).isEqualTo(1);
        assertThat(calculatedCount).isEqualTo(1);

        List<String> allDetailsForRun = jdbcTemplate.queryForList(
                "SELECT details::text FROM audit_logs WHERE entity_name = 'PAYROLL_RUN' AND entity_id = ?",
                String.class,
                run.getId());
        assertThat(String.join(" ", allDetailsForRun)).doesNotContain("123456.78");
        assertThat(String.join(" ", allDetailsForRun)).doesNotContain("0.1100"); // EPF rate
    }

    // ---- Group C: failure and rollback ----

    @Test
    void zeroEligibleCandidatesFailsTheRunWithNoEligibleEmployees() {
        int month = 10;

        assertThatThrownBy(() -> payrollRunService.createAndCalculate(month, YEAR, adminUserId))
                .isInstanceOf(PayrollRunFailedException.class)
                .satisfies(ex -> assertThat(((PayrollRunFailedException) ex).getFailureCode())
                        .isEqualTo(PayrollFailureCode.NO_ELIGIBLE_EMPLOYEES));

        PayrollRun run = payrollRunRepository.findByPeriodMonthAndPeriodYear(month, YEAR).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(PayrollRunStatus.FAILED);
        assertThat(payslipRepository.findByPayrollRunId(run.getId(), org.springframework.data.domain.Pageable.unpaged())
                        .getTotalElements())
                .isZero();
        assertAuditActionsExactly(run.getId(), "PAYROLL_RUN_STARTED", "PAYROLL_RUN_FAILED");
    }

    @Test
    void oneCandidateMissingCoverageFailsTheEntireRunWithNoPayslipsPersisted() {
        int month = 11;
        long validCandidate = insertCandidate("A-VALID", "000011110001");
        coverFullMonth(validCandidate, month, new BigDecimal("1000.00"), BigDecimal.ZERO, BigDecimal.ZERO);
        long noCoverageCandidate = insertCandidate("B-NOCOVER", "000011110002");
        // no salary structure inserted for noCoverageCandidate

        assertThatThrownBy(() -> payrollRunService.createAndCalculate(month, YEAR, adminUserId))
                .isInstanceOf(PayrollRunFailedException.class)
                .satisfies(ex -> assertThat(((PayrollRunFailedException) ex).getFailureCode())
                        .isEqualTo(PayrollFailureCode.SALARY_COVERAGE_MISSING));

        PayrollRun run = payrollRunRepository.findByPeriodMonthAndPeriodYear(month, YEAR).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(PayrollRunStatus.FAILED);
        assertThat(payslipRepository.findByPayrollRunId(run.getId(), org.springframework.data.domain.Pageable.unpaged())
                        .getTotalElements())
                .isZero(); // the earlier, otherwise-valid candidate got no payslip either
        assertAuditActionsExactly(run.getId(), "PAYROLL_RUN_STARTED", "PAYROLL_RUN_FAILED");
    }

    @Test
    void ambiguousCoverageFailsTheEntireRunWithNoPayslipsPersisted() {
        int month = 12;
        LocalDate periodStart = LocalDate.of(YEAR, month, 1);
        LocalDate periodEnd = periodStart.withDayOfMonth(periodStart.lengthOfMonth());
        long employeeId = insertCandidate("AMBIG", "000012120001");
        // Two overlapping full-covering structures, inserted directly via raw SQL -- SalaryStructureService's
        // own overlap check would reject this combination; it is only reachable by bypassing the service.
        PayrollTestFixtures.insertSalaryStructure(
                jdbcTemplate, employeeId, new BigDecimal("1000.00"), BigDecimal.ZERO, BigDecimal.ZERO, periodStart, null);
        PayrollTestFixtures.insertSalaryStructure(
                jdbcTemplate, employeeId, new BigDecimal("2000.00"), BigDecimal.ZERO, BigDecimal.ZERO, periodStart, periodEnd);

        assertThatThrownBy(() -> payrollRunService.createAndCalculate(month, YEAR, adminUserId))
                .isInstanceOf(PayrollRunFailedException.class)
                .satisfies(ex -> assertThat(((PayrollRunFailedException) ex).getFailureCode())
                        .isEqualTo(PayrollFailureCode.SALARY_COVERAGE_AMBIGUOUS));

        PayrollRun run = payrollRunRepository.findByPeriodMonthAndPeriodYear(month, YEAR).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(PayrollRunStatus.FAILED);
        assertThat(payslipRepository.findByPayrollRunId(run.getId(), org.springframework.data.domain.Pageable.unpaged())
                        .getTotalElements())
                .isZero();
        assertAuditActionsExactly(run.getId(), "PAYROLL_RUN_STARTED", "PAYROLL_RUN_FAILED");
    }

    @Test
    void laterCandidatePersistenceFailureRollsBackEarlierCandidatesPayslip() {
        int month = 1;
        int rollbackYear = YEAR + 1; // distinct period from the other tests in this class
        long validCandidate = PayrollTestFixtures.insertEmployee(
                jdbcTemplate, departmentId, "A-OK", "000101010001", LocalDate.of(1999, 1, 1), "ACTIVE");
        PayrollTestFixtures.insertSalaryStructure(
                jdbcTemplate,
                validCandidate,
                new BigDecimal("1000.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                LocalDate.of(1999, 1, 1),
                null);
        long overflowCandidate = PayrollTestFixtures.insertEmployee(
                jdbcTemplate, departmentId, "B-OVERFLOW", "000101010002", LocalDate.of(1999, 1, 1), "ACTIVE");
        // Individually legal NUMERIC(12,2) values; their sum (gross pay) exceeds the 10-integer-digit
        // capacity of payslips.gross_pay NUMERIC(12,2), causing a genuine database-level overflow at
        // persistence time -- not a fabricated/mocked failure.
        PayrollTestFixtures.insertSalaryStructure(
                jdbcTemplate,
                overflowCandidate,
                new BigDecimal("9999999999.99"),
                new BigDecimal("1.00"),
                BigDecimal.ZERO,
                LocalDate.of(1999, 1, 1),
                null);

        assertThatThrownBy(() -> payrollRunService.createAndCalculate(month, rollbackYear, adminUserId))
                .isInstanceOf(PayrollRunFailedException.class)
                .satisfies(ex -> assertThat(((PayrollRunFailedException) ex).getFailureCode())
                        .isEqualTo(PayrollFailureCode.PAYROLL_CALCULATION_FAILED));

        PayrollRun run = payrollRunRepository.findByPeriodMonthAndPeriodYear(month, rollbackYear).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(PayrollRunStatus.FAILED);
        assertThat(payslipRepository.findByPayrollRunId(run.getId(), org.springframework.data.domain.Pageable.unpaged())
                        .getTotalElements())
                .isZero(); // the earlier, valid candidate's payslip was rolled back too
        assertAuditActionsExactly(run.getId(), "PAYROLL_RUN_STARTED", "PAYROLL_RUN_FAILED");
    }

    // ---- duplicate period (single-threaded; concurrent race is covered by PayrollRunConcurrencyTest) ----

    @Test
    void duplicatePeriodIsRejectedWithoutStartingASecondRunOrAuditEntry() {
        int month = 6;
        int duplicateYear = YEAR + 2;
        long employeeId = PayrollTestFixtures.insertEmployee(
                jdbcTemplate, departmentId, "DUP", "000201010001", LocalDate.of(1999, 1, 1), "ACTIVE");
        PayrollTestFixtures.insertSalaryStructure(
                jdbcTemplate, employeeId, new BigDecimal("1000.00"), BigDecimal.ZERO, BigDecimal.ZERO, LocalDate.of(1999, 1, 1), null);

        PayrollRun first = payrollRunService.createAndCalculate(month, duplicateYear, adminUserId);

        assertThatThrownBy(() -> payrollRunService.createAndCalculate(month, duplicateYear, adminUserId))
                .isInstanceOf(PayrollRunPeriodAlreadyExistsException.class);

        Integer runCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payroll_runs WHERE period_month = ? AND period_year = ?",
                Integer.class,
                month,
                duplicateYear);
        assertThat(runCount).isEqualTo(1);
        Integer startedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE entity_name = 'PAYROLL_RUN' AND entity_id = ? AND action = 'PAYROLL_RUN_STARTED'",
                Integer.class,
                first.getId());
        assertThat(startedCount).isEqualTo(1);
    }

    private void assertAuditActionsExactly(Long payrollRunId, String... expectedActions) {
        List<String> actions = jdbcTemplate.queryForList(
                "SELECT action FROM audit_logs WHERE entity_name = 'PAYROLL_RUN' AND entity_id = ? ORDER BY id",
                String.class,
                payrollRunId);
        assertThat(actions).containsExactly(expectedActions);
    }

    // ---- Retry (Phase 8B-2B): own reserved year, distinct from every test above in this class ----

    private static final int RETRY_YEAR = YEAR + 10;

    @Test
    void successfulRetryReusesTheSameRunReCalculatesAndEndsCalculated() {
        int month = 1;
        assertThatThrownBy(() -> payrollRunService.createAndCalculate(month, RETRY_YEAR, adminUserId))
                .isInstanceOf(PayrollRunFailedException.class);
        Long runId = jdbcTemplate.queryForObject(
                "SELECT id FROM payroll_runs WHERE period_month = ? AND period_year = ?", Long.class, month, RETRY_YEAR);
        BigDecimal originalEpfRate = jdbcTemplate.queryForObject(
                "SELECT epf_rate_snapshot FROM payroll_runs WHERE id = ?", BigDecimal.class, runId);
        LocalDate originalPeriodStart =
                jdbcTemplate.queryForObject("SELECT period_start FROM payroll_runs WHERE id = ?", LocalDate.class, runId);

        long employeeId = insertCandidate("RETRY-OK", "0010" + month + "01");
        coverFullMonth(employeeId, month, new BigDecimal("2000.00"), BigDecimal.ZERO, BigDecimal.ZERO);

        PayrollRun retried = payrollRunService.retryAndCalculate(runId, adminUserId);

        assertThat(retried.getId()).isEqualTo(runId);
        assertThat(retried.getStatus()).isEqualTo(PayrollRunStatus.CALCULATED);
        assertThat(retried.getFailureReason()).isNull();
        assertThat(retried.getPeriodStart()).isEqualTo(originalPeriodStart);
        assertThat(retried.getEpfRateSnapshot()).isEqualByComparingTo(originalEpfRate);
        assertThat(payslipRepository.findByPayrollRunId(runId, org.springframework.data.domain.Pageable.unpaged())
                        .getTotalElements())
                .isEqualTo(1);
        assertAuditActionsExactly(runId, "PAYROLL_RUN_STARTED", "PAYROLL_RUN_FAILED", "PAYROLL_RUN_RETRIED", "PAYROLL_RUN_CALCULATED");
    }

    @Test
    void successfulRetryAuditDetailsContainNoCompensationValues() {
        int month = 2;
        assertThatThrownBy(() -> payrollRunService.createAndCalculate(month, RETRY_YEAR, adminUserId))
                .isInstanceOf(PayrollRunFailedException.class);
        Long runId = jdbcTemplate.queryForObject(
                "SELECT id FROM payroll_runs WHERE period_month = ? AND period_year = ?", Long.class, month, RETRY_YEAR);

        long employeeId = insertCandidate("RETRY-AUDIT", "0010" + month + "02");
        BigDecimal secretBasePay = new BigDecimal("654321.00");
        coverFullMonth(employeeId, month, secretBasePay, BigDecimal.ZERO, BigDecimal.ZERO);

        payrollRunService.retryAndCalculate(runId, adminUserId);

        List<String> allDetails = jdbcTemplate.queryForList(
                "SELECT details::text FROM audit_logs WHERE entity_name = 'PAYROLL_RUN' AND entity_id = ?",
                String.class,
                runId);
        String joined = String.join(" ", allDetails);
        assertThat(joined).doesNotContain("654321.00").doesNotContain("0.1100");
    }

    @Test
    void failedRetryEndsFailedWithZeroPayslipsAndPreservesTheStableFailureCode() {
        int month = 3;
        assertThatThrownBy(() -> payrollRunService.createAndCalculate(month, RETRY_YEAR, adminUserId))
                .isInstanceOf(PayrollRunFailedException.class);
        Long runId = jdbcTemplate.queryForObject(
                "SELECT id FROM payroll_runs WHERE period_month = ? AND period_year = ?", Long.class, month, RETRY_YEAR);
        // No candidate coverage is ever added -- retrying must fail again the same way.

        assertThatThrownBy(() -> payrollRunService.retryAndCalculate(runId, adminUserId))
                .isInstanceOf(PayrollRunFailedException.class)
                .satisfies(ex -> assertThat(((PayrollRunFailedException) ex).getFailureCode())
                        .isEqualTo(PayrollFailureCode.NO_ELIGIBLE_EMPLOYEES));

        String status = jdbcTemplate.queryForObject("SELECT status FROM payroll_runs WHERE id = ?", String.class, runId);
        Integer payslipCount =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payslips WHERE payroll_run_id = ?", Integer.class, runId);
        assertThat(status).isEqualTo("FAILED");
        assertThat(payslipCount).isZero();
        assertAuditActionsExactly(
                runId, "PAYROLL_RUN_STARTED", "PAYROLL_RUN_FAILED", "PAYROLL_RUN_RETRIED", "PAYROLL_RUN_FAILED");
    }

    @Test
    void retryingANonFailedRunReturnsTheDomainConflict() {
        int month = 4;
        long calculatedRunId = PayrollTestFixtures.insertPayrollRunWithCreatedAt(
                jdbcTemplate, month, RETRY_YEAR, "CALCULATED", java.time.LocalDateTime.now());
        extraRunIds.add(calculatedRunId);

        assertThatThrownBy(() -> payrollRunService.retryAndCalculate(calculatedRunId, adminUserId))
                .isInstanceOf(PayrollRunNotFailedException.class);

        String status =
                jdbcTemplate.queryForObject("SELECT status FROM payroll_runs WHERE id = ?", String.class, calculatedRunId);
        assertThat(status).isEqualTo("CALCULATED");
    }

    @Test
    void retryingAnUnknownRunReturnsNotFound() {
        assertThatThrownBy(() -> payrollRunService.retryAndCalculate(999999999L, adminUserId))
                .isInstanceOf(PayrollRunNotFoundException.class);
    }
}
