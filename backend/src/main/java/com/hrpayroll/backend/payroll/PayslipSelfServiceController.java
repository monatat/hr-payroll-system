package com.hrpayroll.backend.payroll;

import com.hrpayroll.backend.security.AuthenticatedUser;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * EMPLOYEE-only read access to own payslip history. Deliberately has no {employeeId} path/query
 * variable -- identity comes only from the authenticated JWT principal's employeeId claim,
 * matching EmployeeSelfServiceController/SalaryStructureSelfServiceController's existing pattern.
 * No generic ownership-comparison helper is introduced, per the approved decision.
 */
@RestController
@RequestMapping("/api/employees/me/payslips")
@PreAuthorize("hasRole('EMPLOYEE')")
public class PayslipSelfServiceController {

    private final PayrollQueryService payrollQueryService;

    public PayslipSelfServiceController(PayrollQueryService payrollQueryService) {
        this.payrollQueryService = payrollQueryService;
    }

    @GetMapping
    public PagedModel<PayslipResponse> getOwnPayslips(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return payrollQueryService.listOwnPayslips(requireEmployeeId(principal), pageable);
    }

    private static Long requireEmployeeId(AuthenticatedUser principal) {
        Long employeeId = principal.employeeId();
        if (employeeId == null) {
            // Defensive only: the users.employee_id CHECK constraint and the
            // Phase 4 JWT design guarantee every EMPLOYEE token carries one.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee profile not found");
        }
        return employeeId;
    }
}
