package com.hrpayroll.backend.payroll;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HR_ADMIN-only payslip-by-id endpoint (Phase 8B-2A). Deliberately HR_ADMIN-only for now --
 * EMPLOYEE ownership rules (an employee reading only their own payslip) are Phase 8B-2B's scope,
 * not implemented here.
 */
@RestController
@RequestMapping("/api/payslips")
public class PayslipController {

    private final PayrollQueryService payrollQueryService;

    public PayslipController(PayrollQueryService payrollQueryService) {
        this.payrollQueryService = payrollQueryService;
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('HR_ADMIN')")
    public PayslipResponse getById(@PathVariable Long id) {
        return payrollQueryService.getPayslip(id);
    }
}
