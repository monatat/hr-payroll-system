package com.hrpayroll.backend.payroll;

import com.hrpayroll.backend.audit.AuditService;
import com.hrpayroll.backend.employee.Employee;
import com.hrpayroll.backend.employee.EmployeeRepository;
import com.hrpayroll.backend.employee.EmploymentStatus;
import com.hrpayroll.backend.salary.SalaryStructure;
import com.hrpayroll.backend.salary.SalaryStructureRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the three separately committed transactions of the payroll create-and-calculate
 * workflow. Deliberately a distinct Spring bean from {@link PayrollRunService} (never
 * self-invoked {@code REQUIRES_NEW} methods on one bean) so each {@code @Transactional} method
 * here always goes through its own Spring AOP proxy boundary when called from the
 * non-transactional facade.
 */
@Service
public class PayrollRunTransactionService {

    private static final String ACTION_PAYROLL_RUN_STARTED = "PAYROLL_RUN_STARTED";
    private static final String ACTION_PAYROLL_RUN_CALCULATED = "PAYROLL_RUN_CALCULATED";
    private static final String ACTION_PAYROLL_RUN_FAILED = "PAYROLL_RUN_FAILED";
    private static final String ENTITY_PAYROLL_RUN = "PAYROLL_RUN";
    private static final String UQ_PAYROLL_RUNS_PERIOD = "uq_payroll_runs_period";

    private final PayrollRunRepository payrollRunRepository;
    private final EmployeeRepository employeeRepository;
    private final SalaryStructureRepository salaryStructureRepository;
    private final PayslipRepository payslipRepository;
    private final AuditService auditService;

    public PayrollRunTransactionService(
            PayrollRunRepository payrollRunRepository,
            EmployeeRepository employeeRepository,
            SalaryStructureRepository salaryStructureRepository,
            PayslipRepository payslipRepository,
            AuditService auditService) {
        this.payrollRunRepository = payrollRunRepository;
        this.employeeRepository = employeeRepository;
        this.salaryStructureRepository = salaryStructureRepository;
        this.payslipRepository = payslipRepository;
        this.auditService = auditService;
    }

    /**
     * Transaction 1. Inserts one PROCESSING run with the default rate snapshot and flushes
     * immediately, so uq_payroll_runs_period is evaluated before this method returns -- the
     * database constraint is the sole correctness mechanism for duplicate-period rejection, never
     * a racy pre-check. Records PAYROLL_RUN_STARTED only after the insert has actually survived
     * the flush, so a losing duplicate request never gets a STARTED audit.
     *
     * Explicit REQUIRES_NEW (not default REQUIRED): this transaction's commit must never depend on
     * -- or be undone by -- whatever transaction context the facade happens to be called from. With
     * default REQUIRED, a caller already inside its own transaction would cause this method to join
     * it instead of starting a new one, so an unrelated later rollback of that caller's transaction
     * would silently undo this run's creation too. Proven by
     * PayrollRunTransactionIsolationTest.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PayrollRun createRun(PayrollPeriod period, Long actorUserId) {
        PayrollRateSnapshot rates = PayrollRateSnapshot.demoDefaults();
        PayrollRun run = new PayrollRun(
                period.month(),
                period.year(),
                period.periodStart(),
                period.periodEnd(),
                PayrollRunStatus.PROCESSING,
                rates.epfRate(),
                rates.socsoRate(),
                rates.eisRate(),
                rates.incomeTaxRate());

        PayrollRun saved;
        try {
            saved = payrollRunRepository.saveAndFlush(run);
        } catch (DataIntegrityViolationException ex) {
            if (UQ_PAYROLL_RUNS_PERIOD.equals(extractConstraintName(ex))) {
                throw new PayrollRunPeriodAlreadyExistsException(period.month(), period.year());
            }
            throw ex;
        }

        auditService.record(
                actorUserId,
                ACTION_PAYROLL_RUN_STARTED,
                ENTITY_PAYROLL_RUN,
                saved.getId(),
                Map.of("payrollRunId", saved.getId(), "periodMonth", period.month(), "periodYear", period.year()));

        return saved;
    }

    /**
     * Transaction 2. Locks the run and every candidate, resolves exactly one covering salary
     * structure per candidate, calculates and persists every payslip together, then marks the run
     * CALCULATED -- all inside this one transaction. Any failure (thrown as
     * PayrollRunFailedException) propagates out of this @Transactional method, which rolls the
     * whole thing back: zero payslips, no CALCULATED status change, no CALCULATED audit survive.
     *
     * Explicit REQUIRES_NEW for the same reason as {@link #createRun}: this transaction's outcome
     * (commit on success, full rollback on failure) must be entirely its own, never merged into a
     * caller's surrounding transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PayrollRun calculate(Long payrollRunId, Long actorUserId) {
        PayrollRun run = payrollRunRepository
                .findByIdForUpdate(payrollRunId)
                .orElseThrow(() -> new IllegalStateException("Payroll run " + payrollRunId + " not found."));
        if (run.getStatus() != PayrollRunStatus.PROCESSING) {
            throw new IllegalStateException("Payroll run " + payrollRunId + " is not PROCESSING.");
        }

        List<Employee> candidates =
                employeeRepository.findPayrollCandidatesForUpdate(EmploymentStatus.ACTIVE, run.getPeriodStart());
        if (candidates.isEmpty()) {
            throw new PayrollRunFailedException(
                    payrollRunId, PayrollFailureCode.NO_ELIGIBLE_EMPLOYEES, "No eligible employees for this period.");
        }

        PayrollRateSnapshot rates = new PayrollRateSnapshot(
                run.getEpfRateSnapshot(), run.getSocsoRateSnapshot(), run.getEisRateSnapshot(), run.getIncomeTaxRateSnapshot());

        List<Payslip> payslips = new ArrayList<>(candidates.size());
        for (Employee employee : candidates) {
            List<SalaryStructure> covering = salaryStructureRepository.findCoveringStructures(
                    employee.getId(), run.getPeriodStart(), run.getPeriodEnd());
            if (covering.isEmpty()) {
                throw new PayrollRunFailedException(
                        payrollRunId,
                        PayrollFailureCode.SALARY_COVERAGE_MISSING,
                        "Employee " + employee.getEmployeeCode() + " has no salary structure covering the full period.");
            }
            if (covering.size() > 1) {
                throw new PayrollRunFailedException(
                        payrollRunId,
                        PayrollFailureCode.SALARY_COVERAGE_AMBIGUOUS,
                        "Employee " + employee.getEmployeeCode()
                                + " has more than one salary structure covering the full period.");
            }
            SalaryStructure structure = covering.get(0);

            PayrollCalculationResult result;
            try {
                result = PayrollCalculator.calculate(
                        structure.getBasePay(), structure.getHousingAllowance(), structure.getTransportAllowance(), rates);
            } catch (RuntimeException ex) {
                throw new PayrollRunFailedException(
                        payrollRunId,
                        PayrollFailureCode.PAYROLL_CALCULATION_FAILED,
                        "Payroll calculation failed for employee " + employee.getEmployeeCode() + ".",
                        ex);
            }

            payslips.add(new Payslip(
                    run,
                    employee,
                    employee.getEmployeeCode(),
                    employee.getFullName(),
                    employee.getDepartment().getName(),
                    employee.getJobTitle(),
                    result.basePay(),
                    result.housingAllowance(),
                    result.transportAllowance(),
                    result.grossPay(),
                    result.epfDeduction(),
                    result.socsoDeduction(),
                    result.eisDeduction(),
                    result.incomeTax(),
                    result.totalDeductions(),
                    result.netPay(),
                    null,
                    PdfStatus.PENDING));
        }

        try {
            payslipRepository.saveAll(payslips);
            payslipRepository.flush();
        } catch (RuntimeException ex) {
            throw new PayrollRunFailedException(
                    payrollRunId, PayrollFailureCode.PAYROLL_CALCULATION_FAILED, "Persisting payslips failed.", ex);
        }

        run.markCalculated();
        payrollRunRepository.saveAndFlush(run);

        auditService.record(
                actorUserId,
                ACTION_PAYROLL_RUN_CALCULATED,
                ENTITY_PAYROLL_RUN,
                run.getId(),
                Map.of(
                        "payrollRunId", run.getId(),
                        "periodMonth", run.getPeriodMonth(),
                        "periodYear", run.getPeriodYear(),
                        "payslipCount", payslips.size()));

        return run;
    }

    /**
     * Transaction 3. Always a brand-new, independently proxied transaction (REQUIRES_NEW), so it
     * commits regardless of the calculation transaction having already rolled back by the time the
     * facade calls this. Only the sanitized business-level reason is stored/audited -- never a raw
     * exception message, SQL, or a monetary value.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long payrollRunId, PayrollFailureCode failureCode, String safeReason, Long actorUserId) {
        PayrollRun run = payrollRunRepository
                .findByIdForUpdate(payrollRunId)
                .orElseThrow(() -> new IllegalStateException("Payroll run " + payrollRunId + " not found."));
        if (run.getStatus() != PayrollRunStatus.PROCESSING) {
            throw new IllegalStateException("Payroll run " + payrollRunId + " is not PROCESSING; cannot mark FAILED.");
        }

        run.markFailed(safeReason);
        payrollRunRepository.saveAndFlush(run);

        auditService.record(
                actorUserId,
                ACTION_PAYROLL_RUN_FAILED,
                ENTITY_PAYROLL_RUN,
                run.getId(),
                Map.of(
                        "payrollRunId", run.getId(),
                        "periodMonth", run.getPeriodMonth(),
                        "periodYear", run.getPeriodYear(),
                        "failureCode", failureCode.name()));
    }

    /** Mirrors ApiExceptionHandler's own cause-chain walk -- no new PostgreSQL-driver coupling. */
    private static String extractConstraintName(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof ConstraintViolationException cve) {
                return cve.getConstraintName();
            }
            current = current.getCause();
        }
        return null;
    }
}
