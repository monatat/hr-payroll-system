package com.hrpayroll.backend.payroll;

import com.hrpayroll.backend.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HR_ADMIN-only payroll-run endpoints (Phase 8B-2A): create-and-calculate, list, detail, and a
 * run's payslip list. All four share the {@code /api/payroll-runs} resource path, mirroring
 * LeaveRequestController's precedent for a single controller per resource regardless of HTTP
 * method. Payslip-by-id lives in the separate {@link PayslipController} ({@code /api/payslips}).
 */
@RestController
@RequestMapping("/api/payroll-runs")
public class PayrollRunController {

    private final PayrollRunService payrollRunService;
    private final PayrollQueryService payrollQueryService;
    private final PayrollPdfService payrollPdfService;

    public PayrollRunController(
            PayrollRunService payrollRunService, PayrollQueryService payrollQueryService, PayrollPdfService payrollPdfService) {
        this.payrollRunService = payrollRunService;
        this.payrollQueryService = payrollQueryService;
        this.payrollPdfService = payrollPdfService;
    }

    /**
     * Synchronously creates and calculates the run in one request -- the response always reflects
     * the final CALCULATED state (or the request fails with 422/409/400; there is no intermediate
     * PROCESSING response). The actor id comes only from the authenticated principal; the request
     * body cannot supply it or anything else beyond month/year.
     */
    @PostMapping
    @PreAuthorize("hasRole('HR_ADMIN')")
    public ResponseEntity<PayrollRunResponse> create(
            @Valid @RequestBody CreatePayrollRunRequest request, @AuthenticationPrincipal AuthenticatedUser principal) {
        PayrollRun run = payrollRunService.createAndCalculate(request.month(), request.year(), principal.userId());
        PayrollRunResponse body = PayrollRunResponse.from(run);
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/payroll-runs/" + body.id()))
                .body(body);
    }

    @GetMapping
    @PreAuthorize("hasRole('HR_ADMIN')")
    public PagedModel<PayrollRunResponse> list(
            @RequestParam(required = false) PayrollRunStatus status,
            @RequestParam(required = false) Integer year,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return payrollQueryService.listRuns(status, year, pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('HR_ADMIN')")
    public PayrollRunResponse getById(@PathVariable Long id) {
        return payrollQueryService.getRun(id);
    }

    @GetMapping("/{id}/payslips")
    @PreAuthorize("hasRole('HR_ADMIN')")
    public PagedModel<PayslipResponse> listPayslips(
            @PathVariable Long id,
            @PageableDefault(size = 20, sort = "employeeCodeSnapshot", direction = Sort.Direction.ASC) Pageable pageable) {
        return payrollQueryService.listPayslipsForRun(id, pageable);
    }

    /**
     * Retries a FAILED run in place (Phase 8B-2B) -- reuses the same run id (a replacement row is
     * impossible anyway, since {@code uq_payroll_runs_period} would reject it) and returns the
     * final CALCULATED (or FAILED, via 422) state synchronously, exactly like {@link #create}. No
     * request body: the only input is the path id, and the actor id comes only from the
     * authenticated principal.
     */
    @PostMapping("/{id}/retry")
    @PreAuthorize("hasRole('HR_ADMIN')")
    public PayrollRunResponse retry(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser principal) {
        PayrollRun run = payrollRunService.retryAndCalculate(id, principal.userId());
        return PayrollRunResponse.from(run);
    }

    /**
     * Synchronously generates PDFs for a CALCULATED run's PENDING/FAILED payslips (Phase 8C-2B2).
     * Always returns 200 -- complete success, partial success (some payslips FAILED), and a
     * COMPLETED idempotent no-op all reflect the real outcome in the response body rather than a
     * fake asynchronous/PROCESSING status; only an invalid run state or an unexpected
     * storage-layer failure raises an HTTP error (see {@link PayrollExceptionHandler}). No request
     * body: the only input is the path id, and the actor id comes only from the authenticated
     * principal, exactly like {@link #retry}.
     */
    @PostMapping("/{id}/generate-pdfs")
    @PreAuthorize("hasRole('HR_ADMIN')")
    public PayrollPdfGenerationResponse generatePdfs(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser principal) {
        PayrollPdfGenerationResult result = payrollPdfService.generatePdfs(id, principal.userId());
        return PayrollPdfGenerationResponse.from(result);
    }
}
