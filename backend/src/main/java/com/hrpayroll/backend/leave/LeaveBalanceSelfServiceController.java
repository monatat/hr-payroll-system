package com.hrpayroll.backend.leave;

import com.hrpayroll.backend.security.AuthenticatedUser;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * EMPLOYEE-only read access to own leave balances. Deliberately has no
 * {employeeId} path variable -- identity comes only from the authenticated
 * JWT principal's employeeId claim, matching
 * EmployeeSelfServiceController/SalaryStructureSelfServiceController. No
 * generic ownership-comparison helper is introduced.
 */
@RestController
@RequestMapping("/api/leave-balances/me")
@PreAuthorize("hasRole('EMPLOYEE')")
public class LeaveBalanceSelfServiceController {

    private final LeaveService leaveService;

    public LeaveBalanceSelfServiceController(LeaveService leaveService) {
        this.leaveService = leaveService;
    }

    @GetMapping
    public List<LeaveBalanceResponse> getOwnBalances(@AuthenticationPrincipal AuthenticatedUser principal) {
        return leaveService.getBalancesForEmployee(requireEmployeeId(principal));
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
