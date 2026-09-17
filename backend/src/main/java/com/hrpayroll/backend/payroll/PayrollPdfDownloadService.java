package com.hrpayroll.backend.payroll;

import com.hrpayroll.backend.payroll.pdf.PayslipPdfFileMissingException;
import com.hrpayroll.backend.payroll.pdf.PdfStorageService;
import com.hrpayroll.backend.security.AuthenticatedUser;
import com.hrpayroll.backend.user.UserRole;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads a payslip PDF for download -- a dedicated read-only service, deliberately separate from
 * {@link PayrollPdfService}'s generation transaction. Never resolves or reads the stored path
 * directly; every byte comes from {@link PdfStorageService#load(String)}, obtained lazily through
 * an {@link ObjectProvider} for the same reason {@code PayrollPdfService} does (so an unrelated
 * context boot never creates the development storage directory).
 */
@Service
@Transactional(readOnly = true)
public class PayrollPdfDownloadService {

    private final PayslipRepository payslipRepository;
    private final ObjectProvider<PdfStorageService> pdfStorageServiceProvider;

    public PayrollPdfDownloadService(PayslipRepository payslipRepository, ObjectProvider<PdfStorageService> pdfStorageServiceProvider) {
        this.payslipRepository = payslipRepository;
        this.pdfStorageServiceProvider = pdfStorageServiceProvider;
    }

    /**
     * HR_ADMIN may download any payslip; EMPLOYEE may download only their own. A non-owning
     * EMPLOYEE gets exactly the same 404 as an unknown id -- never 403, which would reveal that
     * the record exists at all. Mirrors {@link PayrollQueryService#getPayslip}'s exact ownership
     * pattern. PENDING/FAILED payslips are rejected before ever touching storage; a GENERATED
     * payslip with a missing/blank stored path is treated as a server-side integrity failure, never
     * a 409 -- it should never happen if generation succeeded and nothing external tampered with
     * the row.
     */
    public PayslipPdfDownload download(Long payslipId, AuthenticatedUser principal) {
        Payslip payslip = payslipRepository.findWithDetailsById(payslipId).orElseThrow(PayslipNotFoundException::new);

        boolean isOwnPayslip = payslip.getEmployee().getId().equals(principal.employeeId());
        if (UserRole.EMPLOYEE.name().equals(principal.role()) && !isOwnPayslip) {
            throw new PayslipNotFoundException();
        }

        PdfStatus pdfStatus = payslip.getPdfStatus();
        if (pdfStatus == PdfStatus.PENDING || pdfStatus == PdfStatus.FAILED) {
            throw new PayslipPdfNotAvailableException();
        }

        String pdfPath = payslip.getPdfPath();
        if (pdfPath == null || pdfPath.isBlank()) {
            throw new PayslipPdfFileMissingException();
        }

        byte[] content = pdfStorageServiceProvider.getObject().load(pdfPath);
        String filename = buildFilename(payslip);
        return new PayslipPdfDownload(content, filename);
    }

    /**
     * Safe server-generated filename: {@code payslip-{year}-{twoDigitMonth}-{sanitizedEmployeeCode}.pdf}.
     * Never uses employee name, IC number, bank information, email, or any filesystem path --
     * only the run's own period and a sanitized copy of the employee code snapshot.
     */
    private static String buildFilename(Payslip payslip) {
        int year = payslip.getPayrollRun().getPeriodYear();
        int month = payslip.getPayrollRun().getPeriodMonth();
        String sanitizedCode = sanitizeEmployeeCode(payslip.getEmployeeCodeSnapshot());
        return String.format("payslip-%04d-%02d-%s.pdf", year, month, sanitizedCode);
    }

    /** Replaces every character outside A-Z, a-z, 0-9, underscore, and hyphen with an underscore. */
    private static String sanitizeEmployeeCode(String employeeCode) {
        String sanitized = employeeCode == null ? "" : employeeCode.replaceAll("[^A-Za-z0-9_-]", "_");
        return sanitized.isBlank() ? "UNKNOWN" : sanitized;
    }
}
