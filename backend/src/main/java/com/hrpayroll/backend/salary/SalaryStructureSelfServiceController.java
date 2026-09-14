package com.hrpayroll.backend.salary;

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
 * EMPLOYEE-only read access to own salary history. Deliberately has no
 * {employeeId} path variable -- identity comes only from the authenticated
 * JWT principal's employeeId claim, matching EmployeeSelfServiceController's
 * existing pattern. No generic ownership-comparison helper is introduced,
 * per the approved decision.
 */
@RestController
@RequestMapping("/api/employees/me/salary-structures")
@PreAuthorize("hasRole('EMPLOYEE')")
public class SalaryStructureSelfServiceController {

    private final SalaryStructureService salaryStructureService;

    public SalaryStructureSelfServiceController(SalaryStructureService salaryStructureService) {
        this.salaryStructureService = salaryStructureService;
    }

    @GetMapping
    public List<SalaryStructureResponse> getOwnSalaryHistory(@AuthenticationPrincipal AuthenticatedUser principal) {
        return salaryStructureService.listForEmployee(requireEmployeeId(principal));
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
