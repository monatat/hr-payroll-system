package com.hrpayroll.backend.employee;

import com.hrpayroll.backend.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HR_ADMIN-only employee management (list/detail/create/update/status).
 * Kept as a separate controller from the EMPLOYEE self-service endpoints
 * (see EmployeeSelfServiceController) so the security boundary between the
 * two is visible in the package structure, not just in annotations.
 */
@RestController
@RequestMapping("/api/employees")
@PreAuthorize("hasRole('HR_ADMIN')")
public class EmployeeController {

    private final EmployeeService employeeService;

    public EmployeeController(EmployeeService employeeService) {
        this.employeeService = employeeService;
    }

    @GetMapping
    public PagedModel<EmployeeResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) EmploymentStatus status,
            @PageableDefault(size = 20, sort = "fullName") Pageable pageable) {
        return employeeService.list(q, departmentId, status, pageable);
    }

    @GetMapping("/{id}")
    public EmployeeResponse getById(@PathVariable Long id) {
        return employeeService.getById(id);
    }

    @PostMapping
    public ResponseEntity<EmployeeResponse> create(
            @Valid @RequestBody CreateEmployeeRequest request, @AuthenticationPrincipal AuthenticatedUser principal) {
        EmployeeResponse created = employeeService.create(request, principal.userId());
        return ResponseEntity.created(URI.create("/api/employees/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public EmployeeResponse update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateEmployeeRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return employeeService.update(id, request, principal.userId());
    }

    @PatchMapping("/{id}/status")
    public EmployeeResponse changeStatus(
            @PathVariable Long id,
            @Valid @RequestBody UpdateEmployeeStatusRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return employeeService.changeStatus(id, request, principal.userId());
    }
}
