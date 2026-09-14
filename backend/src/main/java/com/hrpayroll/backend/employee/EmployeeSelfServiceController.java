package com.hrpayroll.backend.employee;

import com.hrpayroll.backend.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * EMPLOYEE-only self-service. Deliberately has no {id} path variable
 * anywhere -- the employee identity comes only from the authenticated JWT
 * principal, so there is no client-suppliable ID to spoof and therefore no
 * need for a reusable ownership-comparison helper.
 */
@RestController
@RequestMapping("/api/employees/me")
@PreAuthorize("hasRole('EMPLOYEE')")
public class EmployeeSelfServiceController {

    private final EmployeeService employeeService;

    public EmployeeSelfServiceController(EmployeeService employeeService) {
        this.employeeService = employeeService;
    }

    @GetMapping
    public EmployeeResponse getOwnProfile(@AuthenticationPrincipal AuthenticatedUser principal) {
        return employeeService.getOwnProfile(requireEmployeeId(principal));
    }

    @PutMapping
    public EmployeeResponse updateOwnProfile(
            @Valid @RequestBody UpdateOwnProfileRequest request, @AuthenticationPrincipal AuthenticatedUser principal) {
        Long employeeId = requireEmployeeId(principal);
        return employeeService.updateOwnProfile(employeeId, request, principal.userId());
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
