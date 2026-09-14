package com.hrpayroll.backend.department;

import com.hrpayroll.backend.audit.AuditService;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class DepartmentService {

    private static final String ACTION_DEPARTMENT_CREATED = "DEPARTMENT_CREATED";
    private static final String ENTITY_DEPARTMENT = "DEPARTMENT";

    private final DepartmentRepository departmentRepository;
    private final AuditService auditService;

    public DepartmentService(DepartmentRepository departmentRepository, AuditService auditService) {
        this.departmentRepository = departmentRepository;
        this.auditService = auditService;
    }

    public List<DepartmentResponse> listAll() {
        return departmentRepository.findAllByOrderByNameAsc().stream()
                .map(DepartmentResponse::from)
                .toList();
    }

    public DepartmentResponse create(CreateDepartmentRequest request, Long actorUserId) {
        // Uniqueness is authoritatively enforced by the DB's uq_departments_name
        // constraint; a save() that violates it throws DataIntegrityViolationException,
        // translated to 409 by ApiExceptionHandler. No racy pre-check here.
        Department saved = departmentRepository.save(new Department(request.name()));

        auditService.record(
                actorUserId, ACTION_DEPARTMENT_CREATED, ENTITY_DEPARTMENT, saved.getId(),
                Map.of("name", saved.getName()));

        return DepartmentResponse.from(saved);
    }
}
