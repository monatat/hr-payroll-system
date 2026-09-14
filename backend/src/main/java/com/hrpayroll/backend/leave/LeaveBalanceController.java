package com.hrpayroll.backend.leave;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HR_ADMIN-only view of a specific employee's leave balances -- mirrors
 * SalaryStructureController's per-employee GET (Phase 6). Approved as a
 * Phase 7 addition beyond the originally-listed API surface, so an admin
 * reviewing a request can see balance context without a direct DB query.
 */
@RestController
@RequestMapping("/api/employees/{employeeId}/leave-balances")
@PreAuthorize("hasRole('HR_ADMIN')")
public class LeaveBalanceController {

    private final LeaveService leaveService;

    public LeaveBalanceController(LeaveService leaveService) {
        this.leaveService = leaveService;
    }

    @GetMapping
    public List<LeaveBalanceResponse> listForEmployee(@PathVariable Long employeeId) {
        return leaveService.getBalancesForEmployee(employeeId);
    }
}
