package com.hrpayroll.backend.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.hrpayroll.backend.support.PayrollTestFixtures;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves {@link PayrollPdfService#generatePdfs} is a genuinely independent, separately committed
 * transaction -- unaffected by any transaction the caller happens to already be inside. Calls the
 * real service from inside an outer TransactionTemplate transaction that is always rolled back,
 * then verifies via a fresh query that the generation work survived that rollback regardless.
 * Mirrors PayrollRunTransactionIsolationTest's established style exactly.
 */
@SpringBootTest
class PayrollPdfServiceTransactionIsolationTest {

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void pdfStorageProperties(DynamicPropertyRegistry registry) {
        registry.add("app.payroll.pdf-storage-dir", () -> storageRoot.toString());
    }

    @Autowired
    private PayrollPdfService payrollPdfService;

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
    void generationSurvivesAnOuterTransactionThatIsLaterRolledBack() {
        int month = 2;
        int year = PayrollTestFixtures.PDF_TEST_PERIOD_YEAR_MAX; // own reserved period, distinct from the concurrency test above
        long employeeId = PayrollTestFixtures.insertEmployee(
                jdbcTemplate, departmentId, "PDFISO", "001315010001", LocalDate.of(1999, 1, 1), "ACTIVE");
        PayrollTestFixtures.insertSalaryStructure(
                jdbcTemplate, employeeId, new BigDecimal("1600.00"), BigDecimal.ZERO, BigDecimal.ZERO, LocalDate.of(1999, 1, 1), null);
        PayrollRun run = payrollRunService.createAndCalculate(month, year, adminUserId);

        TransactionTemplate outerTransaction = new TransactionTemplate(transactionManager);
        outerTransaction.execute(status -> {
            payrollPdfService.generatePdfs(run.getId(), adminUserId);
            status.setRollbackOnly();
            return null;
        });
        // The outer transaction has now rolled back. If generatePdfs had joined it (default
        // REQUIRED propagation instead of REQUIRES_NEW), everything below would not exist.

        PayrollRun reloaded = payrollRunRepository.findById(run.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PayrollRunStatus.COMPLETED);

        List<Payslip> payslips = payslipRepository.findByPayrollRunIdOrderByIdAsc(run.getId());
        assertThat(payslips).hasSize(1);
        assertThat(payslips.get(0).getPdfStatus()).isEqualTo(PdfStatus.GENERATED);

        List<String> actions = jdbcTemplate.queryForList(
                "SELECT action FROM audit_logs WHERE entity_name = 'PAYROLL_RUN' AND entity_id = ? "
                        + "AND action IN ('PAYROLL_RUN_PDF_GENERATION_FINISHED','PAYROLL_RUN_COMPLETED') ORDER BY id",
                String.class,
                run.getId());
        assertThat(actions).containsExactly("PAYROLL_RUN_PDF_GENERATION_FINISHED", "PAYROLL_RUN_COMPLETED");
    }
}
