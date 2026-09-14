package com.hrpayroll.backend.employee;

import com.hrpayroll.backend.audit.AuditService;
import com.hrpayroll.backend.security.AuthenticatedUser;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * HR_ADMIN-only access to unmasked employee IC/bank data. No general
 * employee CRUD/listing/self-service endpoint exists yet -- those belong to
 * a later phase.
 */
@RestController
@RequestMapping("/api/employees")
public class EmployeeSensitiveController {

    private static final String ACTION_SENSITIVE_DATA_ACCESS = "SENSITIVE_DATA_ACCESS";
    private static final String ENTITY_EMPLOYEE = "EMPLOYEE";

    private final EmployeeRepository employeeRepository;
    private final AuditService auditService;

    public EmployeeSensitiveController(EmployeeRepository employeeRepository, AuditService auditService) {
        this.employeeRepository = employeeRepository;
        this.auditService = auditService;
    }

    @GetMapping("/{id}/sensitive")
    @PreAuthorize("hasRole('HR_ADMIN')")
    public EmployeeSensitiveResponse getSensitive(
            @PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser principal) {
        Employee employee = employeeRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found"));

        auditService.record(
                principal.userId(),
                ACTION_SENSITIVE_DATA_ACCESS,
                ENTITY_EMPLOYEE,
                employee.getId(),
                Map.of("fields", List.of("icNumber", "bankName", "bankAccountNo")));

        return new EmployeeSensitiveResponse(
                employee.getId(), employee.getIcNumber(), employee.getBankName(), employee.getBankAccountNo());
    }
}
