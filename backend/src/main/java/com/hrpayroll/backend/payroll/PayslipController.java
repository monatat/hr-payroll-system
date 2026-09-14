package com.hrpayroll.backend.payroll;

import com.hrpayroll.backend.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Payslip-by-id endpoint. Deliberately carries no {@code @PreAuthorize} -- any authenticated role
 * may call it (the security filter chain's blanket {@code anyRequest().authenticated()} already
 * covers 401), and {@link PayrollQueryService#getPayslip} enforces the actual rule: HR_ADMIN may
 * read any payslip, EMPLOYEE only their own (a non-owning EMPLOYEE gets 404, never 403 --
 * mirrors LeaveRequestController.getById's exact convention).
 */
@RestController
@RequestMapping("/api/payslips")
public class PayslipController {

    private final PayrollQueryService payrollQueryService;

    public PayslipController(PayrollQueryService payrollQueryService) {
        this.payrollQueryService = payrollQueryService;
    }

    @GetMapping("/{id}")
    public PayslipResponse getById(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser principal) {
        return payrollQueryService.getPayslip(id, principal);
    }
}
