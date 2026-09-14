package com.hrpayroll.backend.salary;

import com.hrpayroll.backend.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * HR_ADMIN-only salary structure administration. No class-level
 * {@code @RequestMapping} -- the three endpoints live under two different
 * URL roots ({@code /api/employees/{employeeId}/salary-structures} and
 * {@code /api/salary-structures/{id}}), matching the approved API design.
 */
@RestController
@PreAuthorize("hasRole('HR_ADMIN')")
public class SalaryStructureController {

    private final SalaryStructureService salaryStructureService;

    public SalaryStructureController(SalaryStructureService salaryStructureService) {
        this.salaryStructureService = salaryStructureService;
    }

    @GetMapping("/api/employees/{employeeId}/salary-structures")
    public List<SalaryStructureResponse> listForEmployee(@PathVariable Long employeeId) {
        return salaryStructureService.listForEmployee(employeeId);
    }

    @PostMapping("/api/employees/{employeeId}/salary-structures")
    public ResponseEntity<SalaryStructureResponse> create(
            @PathVariable Long employeeId,
            @Valid @RequestBody CreateSalaryStructureRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        SalaryStructureResponse created = salaryStructureService.create(employeeId, request, principal.userId());
        return ResponseEntity.created(URI.create("/api/salary-structures/" + created.id())).body(created);
    }

    @GetMapping("/api/salary-structures/{id}")
    public SalaryStructureResponse getById(@PathVariable Long id) {
        return salaryStructureService.getById(id);
    }
}
