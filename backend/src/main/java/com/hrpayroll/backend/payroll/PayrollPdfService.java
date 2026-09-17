package com.hrpayroll.backend.payroll;

import com.hrpayroll.backend.audit.AuditService;
import com.hrpayroll.backend.payroll.pdf.PayrollPdfStorageException;
import com.hrpayroll.backend.payroll.pdf.PayslipPdfData;
import com.hrpayroll.backend.payroll.pdf.PayslipPdfGenerationException;
import com.hrpayroll.backend.payroll.pdf.PayslipPdfRenderer;
import com.hrpayroll.backend.payroll.pdf.PdfStorageService;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates payslip PDF generation for one payroll run (Phase 8C-2B1). Deliberately a distinct
 * bean from {@link PayrollRunTransactionService}: PDF generation supports partial success (one
 * failed document must never undo another's), the opposite of calculation's all-or-nothing
 * rollback, so it cannot reuse that service's three-way {@code REQUIRES_NEW} split -- one
 * {@code REQUIRES_NEW} transaction here holds the run-row lock for the whole batch and simply
 * never lets a per-document failure escape the loop.
 *
 * <p>Obtains {@link PdfStorageService} through an {@link ObjectProvider} rather than a direct
 * constructor dependency so that constructing this (eager) service bean never itself triggers
 * {@code PdfStorageService}'s directory-creating constructor -- see {@code PdfStorageConfig}.
 */
@Service
public class PayrollPdfService {

    private static final String ACTION_FINISHED = "PAYROLL_RUN_PDF_GENERATION_FINISHED";
    private static final String ACTION_COMPLETED = "PAYROLL_RUN_COMPLETED";
    private static final String ENTITY_PAYROLL_RUN = "PAYROLL_RUN";

    private final PayrollRunRepository payrollRunRepository;
    private final PayslipRepository payslipRepository;
    private final ObjectProvider<PdfStorageService> pdfStorageServiceProvider;
    private final PayslipPdfRenderer payslipPdfRenderer;
    private final AuditService auditService;

    private volatile Runnable preFlushHookForTesting = () -> {
    };

    public PayrollPdfService(
            PayrollRunRepository payrollRunRepository,
            PayslipRepository payslipRepository,
            ObjectProvider<PdfStorageService> pdfStorageServiceProvider,
            PayslipPdfRenderer payslipPdfRenderer,
            AuditService auditService) {
        this.payrollRunRepository = payrollRunRepository;
        this.payslipRepository = payslipRepository;
        this.pdfStorageServiceProvider = pdfStorageServiceProvider;
        this.payslipPdfRenderer = payslipPdfRenderer;
        this.auditService = auditService;
    }

    /**
     * Test-only seam: overrides the no-op hook run immediately before the post-loop flush, so a
     * test can inject a deterministic persistence failure at exactly that point without weakening
     * any DB constraint or production validation. Production callers never touch this.
     */
    void setPreFlushHookForTesting(Runnable hook) {
        this.preFlushHookForTesting = hook;
    }

    /**
     * Generates PDFs for every PENDING/FAILED payslip in one CALCULATED payroll run, completing
     * the run once every payslip is GENERATED. Locks the run row for the whole batch
     * ({@code findByIdForUpdate}), re-checks status under that lock, and re-derives period
     * month/year from the already-locked run rather than re-reading any other association.
     *
     * <p>Per-document failures ({@link PayslipPdfGenerationException}/{@link
     * PayrollPdfStorageException}) are caught individually so one failure never blocks another
     * payslip's success; entity mutations happen in-memory during the loop and are flushed once,
     * together, after the loop -- outside every per-document catch block -- so a genuine
     * persistence/database failure at that point is never caught here and always escapes to roll
     * back this {@code REQUIRES_NEW} transaction. A file is always atomically moved into place
     * before its payslip is marked GENERATED; if a later persistence failure rolls the transaction
     * back regardless, that already-written file becomes a harmless, deterministic orphan that a
     * future retry silently overwrites -- Postgres cannot roll back a filesystem write.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PayrollPdfGenerationResult generatePdfs(Long payrollRunId, Long actorUserId) {
        PayrollRun run =
                payrollRunRepository.findByIdForUpdate(payrollRunId).orElseThrow(PayrollRunNotFoundException::new);

        if (run.getStatus() == PayrollRunStatus.COMPLETED) {
            int totalPayslips = payslipRepository.findByPayrollRunIdOrderByIdAsc(payrollRunId).size();
            return new PayrollPdfGenerationResult(run.getId(), PayrollRunStatus.COMPLETED, 0, 0, totalPayslips);
        }
        if (run.getStatus() != PayrollRunStatus.CALCULATED) {
            throw new PayrollRunNotCalculatedException(payrollRunId);
        }

        List<Payslip> payslips = payslipRepository.findByPayrollRunIdOrderByIdAsc(payrollRunId);
        if (payslips.isEmpty()) {
            throw new PayrollRunHasNoPayslipsException(payrollRunId);
        }

        PdfStorageService storageService = pdfStorageServiceProvider.getObject();

        int generatedCount = 0;
        int failedCount = 0;
        int skippedCount = 0;

        for (Payslip payslip : payslips) {
            if (payslip.getPdfStatus() == PdfStatus.GENERATED) {
                skippedCount++;
                continue;
            }
            try {
                PayslipPdfData data = toPdfData(payslip, run);
                byte[] pdfBytes = payslipPdfRenderer.generate(data);
                String relativePath = storageService.store(run.getId(), payslip.getId(), pdfBytes);
                payslip.markPdfGenerated(relativePath);
                generatedCount++;
            } catch (PayslipPdfGenerationException | PayrollPdfStorageException ex) {
                payslip.markPdfFailed();
                failedCount++;
            }
        }

        preFlushHookForTesting.run();
        payslipRepository.saveAll(payslips);
        payslipRepository.flush();

        auditService.record(
                actorUserId,
                ACTION_FINISHED,
                ENTITY_PAYROLL_RUN,
                run.getId(),
                Map.of(
                        "payrollRunId", run.getId(),
                        "periodMonth", run.getPeriodMonth(),
                        "periodYear", run.getPeriodYear(),
                        "generatedCount", generatedCount,
                        "failedCount", failedCount,
                        "skippedCount", skippedCount));

        PayrollRunStatus finalStatus = run.getStatus();
        boolean everyPayslipGenerated = payslips.stream().allMatch(p -> p.getPdfStatus() == PdfStatus.GENERATED);
        if (everyPayslipGenerated) {
            run.markCompleted();
            payrollRunRepository.saveAndFlush(run);
            finalStatus = run.getStatus();
            auditService.record(
                    actorUserId,
                    ACTION_COMPLETED,
                    ENTITY_PAYROLL_RUN,
                    run.getId(),
                    Map.of(
                            "payrollRunId", run.getId(),
                            "periodMonth", run.getPeriodMonth(),
                            "periodYear", run.getPeriodYear(),
                            "payslipCount", payslips.size()));
        }

        return new PayrollPdfGenerationResult(run.getId(), finalStatus, generatedCount, failedCount, skippedCount);
    }

    private static PayslipPdfData toPdfData(Payslip payslip, PayrollRun run) {
        return new PayslipPdfData(
                run.getPeriodMonth(),
                run.getPeriodYear(),
                payslip.getEmployeeCodeSnapshot(),
                payslip.getEmployeeNameSnapshot(),
                payslip.getDepartmentNameSnapshot(),
                payslip.getJobTitleSnapshot(),
                payslip.getBasePay(),
                payslip.getHousingAllowance(),
                payslip.getTransportAllowance(),
                payslip.getGrossPay(),
                payslip.getEpfDeduction(),
                payslip.getSocsoDeduction(),
                payslip.getEisDeduction(),
                payslip.getIncomeTax(),
                payslip.getTotalDeductions(),
                payslip.getNetPay());
    }
}
