package com.hrpayroll.backend.payroll;

import com.hrpayroll.backend.security.AuthenticatedUser;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Payslip-by-id and payslip-PDF-download endpoints. Neither carries an {@code @PreAuthorize} --
 * any authenticated role may call them (the security filter chain's blanket
 * {@code anyRequest().authenticated()} already covers 401), and the underlying service enforces
 * the actual rule: HR_ADMIN may read/download any payslip, EMPLOYEE only their own (a non-owning
 * EMPLOYEE gets 404, never 403 -- mirrors LeaveRequestController.getById's exact convention).
 */
@RestController
@RequestMapping("/api/payslips")
public class PayslipController {

    private final PayrollQueryService payrollQueryService;
    private final PayrollPdfDownloadService payrollPdfDownloadService;

    public PayslipController(PayrollQueryService payrollQueryService, PayrollPdfDownloadService payrollPdfDownloadService) {
        this.payrollQueryService = payrollQueryService;
        this.payrollPdfDownloadService = payrollPdfDownloadService;
    }

    @GetMapping("/{id}")
    public PayslipResponse getById(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser principal) {
        return payrollQueryService.getPayslip(id, principal);
    }

    /**
     * Streams a generated payslip PDF. Content-Length is set from the exact byte count returned by
     * {@link PayrollPdfDownloadService}; Cache-Control is set explicitly (never a shared/proxy
     * cache, since this is personal financial data). The filename is entirely server-generated --
     * this endpoint never accepts a client-supplied filename, path, or employee id.
     */
    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> downloadPdf(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser principal) {
        PayslipPdfDownload download = payrollPdfDownloadService.download(id, principal);
        byte[] content = download.content();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(content.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + download.filename() + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(content);
    }
}
