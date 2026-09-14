package com.hrpayroll.backend.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.hrpayroll.backend.support.PayrollTestFixtures;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves the three PayrollRunTransactionService operations are each a genuinely independent,
 * separately committed transaction -- unaffected by any transaction the caller happens to already
 * be inside. Calls the real facade from inside an outer TransactionTemplate transaction that is
 * always rolled back, then verifies via a fresh query that the payroll work survived that rollback
 * regardless. If any of createRun/calculate/markFailed instead joined the caller's transaction
 * (default REQUIRED propagation), the outer rollback would undo it too, and these assertions would
 * fail.
 */
@SpringBootTest
class PayrollRunTransactionIsolationTest {

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

    @Autowired
    private PlatformTransactionManager transactionManager;

    private long departmentId;
    private long adminUserId;

    @BeforeEach
    void setUp() {
        PayrollTestFixtures.cleanup(jdbcTemplate);
        departmentId = PayrollTestFixtures.installDepartment(jdbcTemplate);
        adminUserId = PayrollTestFixtures.installAdminUser(jdbcTemplate, passwordEncoder);
    }

    @AfterEach
    void tearDown() {
        PayrollTestFixtures.cleanup(jdbcTemplate);
    }

    @Test
    void successfulRunSurvivesAnOuterTransactionThatIsLaterRolledBack() {
        int month = 1;
        int year = 2003; // this test class's own reserved period, distinct from every other payroll test class
        long employeeId = PayrollTestFixtures.insertEmployee(
                jdbcTemplate, departmentId, "ISO-OK", "000501010001", LocalDate.of(1999, 1, 1), "ACTIVE");
        PayrollTestFixtures.insertSalaryStructure(
                jdbcTemplate, employeeId, new BigDecimal("1000.00"), BigDecimal.ZERO, BigDecimal.ZERO, LocalDate.of(1999, 1, 1), null);

        TransactionTemplate outerTransaction = new TransactionTemplate(transactionManager);
        Long[] runIdHolder = new Long[1];
        outerTransaction.execute(status -> {
            PayrollRun run = payrollRunService.createAndCalculate(month, year, adminUserId);
            runIdHolder[0] = run.getId();
            status.setRollbackOnly();
            return null;
        });
        // The outer transaction has now rolled back. If createRun/calculate had joined it (default
        // REQUIRED propagation instead of REQUIRES_NEW), everything below would not exist.

        PayrollRun reloaded = payrollRunRepository.findById(runIdHolder[0]).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PayrollRunStatus.CALCULATED);
        assertThat(payslipRepository.findByPayrollRunId(runIdHolder[0], org.springframework.data.domain.Pageable.unpaged())
                        .getTotalElements())
                .isEqualTo(1);
        assertAuditActionsExactly(runIdHolder[0], "PAYROLL_RUN_STARTED", "PAYROLL_RUN_CALCULATED");
    }

    @Test
    void failedRunSurvivesAnOuterTransactionThatIsLaterRolledBack() {
        int month = 1;
        int year = 2004; // this test class's own reserved period, distinct from the success test above
        // No candidate salary coverage inserted at all -- the run will fail with NO_ELIGIBLE_EMPLOYEES.

        TransactionTemplate outerTransaction = new TransactionTemplate(transactionManager);
        Long[] runIdHolder = new Long[1];
        PayrollFailureCode[] codeHolder = new PayrollFailureCode[1];
        outerTransaction.execute(status -> {
            try {
                payrollRunService.createAndCalculate(month, year, adminUserId);
            } catch (PayrollRunFailedException ex) {
                runIdHolder[0] = ex.getPayrollRunId();
                codeHolder[0] = ex.getFailureCode();
            }
            status.setRollbackOnly();
            return null;
        });
        // The outer transaction has now rolled back. If createRun/calculate/markFailed had joined
        // it, the run would not exist at all below -- not even in FAILED state.

        assertThat(runIdHolder[0]).isNotNull();
        PayrollRun reloaded = payrollRunRepository.findById(runIdHolder[0]).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PayrollRunStatus.FAILED);
        assertThat(payslipRepository.findByPayrollRunId(runIdHolder[0], org.springframework.data.domain.Pageable.unpaged())
                        .getTotalElements())
                .isZero();
        assertAuditActionsExactly(runIdHolder[0], "PAYROLL_RUN_STARTED", "PAYROLL_RUN_FAILED");
        assertThat(codeHolder[0]).isEqualTo(PayrollFailureCode.NO_ELIGIBLE_EMPLOYEES);
    }

    @Test
    void retrySurvivesAnOuterTransactionThatIsLaterRolledBack() {
        int month = 1;
        int year = 2011; // this test class's own reserved period, distinct from the two tests above
        // No candidate yet -- the initial create fails with NO_ELIGIBLE_EMPLOYEES, leaving a FAILED run to retry.
        try {
            payrollRunService.createAndCalculate(month, year, adminUserId);
        } catch (PayrollRunFailedException ignored) {
            // expected: the run now exists, FAILED
        }
        Long runId = jdbcTemplate.queryForObject(
                "SELECT id FROM payroll_runs WHERE period_month = ? AND period_year = ?", Long.class, month, year);

        long employeeId = PayrollTestFixtures.insertEmployee(
                jdbcTemplate, departmentId, "ISO-RETRY", "000501010002", LocalDate.of(1999, 1, 1), "ACTIVE");
        PayrollTestFixtures.insertSalaryStructure(
                jdbcTemplate, employeeId, new BigDecimal("1000.00"), BigDecimal.ZERO, BigDecimal.ZERO, LocalDate.of(1999, 1, 1), null);

        TransactionTemplate outerTransaction = new TransactionTemplate(transactionManager);
        outerTransaction.execute(status -> {
            payrollRunService.retryAndCalculate(runId, adminUserId);
            status.setRollbackOnly();
            return null;
        });
        // The outer transaction has now rolled back. If prepareRetry/calculate had joined it, the
        // retry's effects (PROCESSING->CALCULATED, the payslip, the RETRIED/CALCULATED audits)
        // would all have been undone too.

        PayrollRun reloaded = payrollRunRepository.findById(runId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PayrollRunStatus.CALCULATED);
        assertThat(payslipRepository.findByPayrollRunId(runId, org.springframework.data.domain.Pageable.unpaged())
                        .getTotalElements())
                .isEqualTo(1);
        assertAuditActionsExactly(
                runId, "PAYROLL_RUN_STARTED", "PAYROLL_RUN_FAILED", "PAYROLL_RUN_RETRIED", "PAYROLL_RUN_CALCULATED");
    }

    private void assertAuditActionsExactly(Long payrollRunId, String... expectedActions) {
        List<String> actions = jdbcTemplate.queryForList(
                "SELECT action FROM audit_logs WHERE entity_name = 'PAYROLL_RUN' AND entity_id = ? ORDER BY id",
                String.class,
                payrollRunId);
        assertThat(actions).containsExactly(expectedActions);
    }
}
