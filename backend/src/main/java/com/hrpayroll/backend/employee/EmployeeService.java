package com.hrpayroll.backend.employee;

import com.hrpayroll.backend.audit.AuditService;
import com.hrpayroll.backend.department.Department;
import com.hrpayroll.backend.department.DepartmentRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class EmployeeService {

    private static final String ACTION_EMPLOYEE_CREATED = "EMPLOYEE_CREATED";
    private static final String ACTION_EMPLOYEE_UPDATED = "EMPLOYEE_UPDATED";
    private static final String ACTION_EMPLOYEE_STATUS_CHANGED = "EMPLOYEE_STATUS_CHANGED";
    private static final String ACTION_EMPLOYEE_SELF_PROFILE_UPDATED = "EMPLOYEE_SELF_PROFILE_UPDATED";
    private static final String ENTITY_EMPLOYEE = "EMPLOYEE";

    private final EmployeeRepository employeeRepository;
    private final DepartmentRepository departmentRepository;
    private final AuditService auditService;

    public EmployeeService(
            EmployeeRepository employeeRepository, DepartmentRepository departmentRepository, AuditService auditService) {
        this.employeeRepository = employeeRepository;
        this.departmentRepository = departmentRepository;
        this.auditService = auditService;
    }

    public PagedModel<EmployeeResponse> list(String q, Long departmentId, EmploymentStatus status, Pageable pageable) {
        Pageable withDeterministicTiebreaker = withIdTiebreaker(pageable);
        String likePattern = (q == null || q.isBlank()) ? null : "%" + q.trim().toLowerCase() + "%";

        Page<Employee> page = employeeRepository.search(likePattern, departmentId, status, withDeterministicTiebreaker);
        return new PagedModel<>(page.map(EmployeeResponse::from));
    }

    public EmployeeResponse getById(Long id) {
        return EmployeeResponse.from(findEmployeeOrThrow(id));
    }

    @Transactional
    public EmployeeResponse create(CreateEmployeeRequest request, Long actorUserId) {
        Department department = findDepartmentOrThrow(request.departmentId());

        EmploymentStatus status = request.employmentStatus() != null ? request.employmentStatus() : EmploymentStatus.ACTIVE;

        Employee employee = new Employee(
                request.employeeCode(),
                request.fullName(),
                request.icNumber(),
                department,
                request.jobTitle(),
                status,
                request.hireDate(),
                request.bankName(),
                request.bankAccountNo(),
                request.phoneNumber());

        // Uniqueness (employee_code, ic_number) is authoritatively enforced by
        // the DB constraints; save() throws DataIntegrityViolationException on
        // violation, translated to 409 by ApiExceptionHandler.
        Employee saved = employeeRepository.save(employee);

        auditService.record(
                actorUserId, ACTION_EMPLOYEE_CREATED, ENTITY_EMPLOYEE, saved.getId(),
                Map.of("employeeCode", saved.getEmployeeCode(), "departmentId", department.getId()));

        return EmployeeResponse.from(saved);
    }

    @Transactional
    public EmployeeResponse update(Long id, UpdateEmployeeRequest request, Long actorUserId) {
        Employee employee = findEmployeeOrThrow(id);
        Department newDepartment = findDepartmentOrThrow(request.departmentId());

        List<String> changedFields = new ArrayList<>();
        if (!Objects.equals(employee.getFullName(), request.fullName())) {
            changedFields.add("fullName");
            employee.setFullName(request.fullName());
        }
        if (request.icNumber() != null && !Objects.equals(employee.getIcNumber(), request.icNumber())) {
            changedFields.add("icNumber");
            employee.setIcNumber(request.icNumber());
        }
        if (!Objects.equals(employee.getDepartment().getId(), request.departmentId())) {
            changedFields.add("departmentId");
            employee.setDepartment(newDepartment);
        }
        if (!Objects.equals(employee.getJobTitle(), request.jobTitle())) {
            changedFields.add("jobTitle");
            employee.setJobTitle(request.jobTitle());
        }
        if (employee.getEmploymentStatus() != request.employmentStatus()) {
            changedFields.add("employmentStatus");
            employee.setEmploymentStatus(request.employmentStatus());
        }
        if (!Objects.equals(employee.getHireDate(), request.hireDate())) {
            changedFields.add("hireDate");
            employee.setHireDate(request.hireDate());
        }
        if (request.bankName() != null && !Objects.equals(employee.getBankName(), request.bankName())) {
            changedFields.add("bankName");
            employee.setBankName(request.bankName());
        }
        if (request.bankAccountNo() != null && !Objects.equals(employee.getBankAccountNo(), request.bankAccountNo())) {
            changedFields.add("bankAccountNo");
            employee.setBankAccountNo(request.bankAccountNo());
        }
        if (!Objects.equals(employee.getPhoneNumber(), request.phoneNumber())) {
            changedFields.add("phoneNumber");
            employee.setPhoneNumber(request.phoneNumber());
        }

        if (!changedFields.isEmpty()) {
            auditService.record(
                    actorUserId, ACTION_EMPLOYEE_UPDATED, ENTITY_EMPLOYEE, employee.getId(),
                    Map.of("fields", changedFields));
        }

        return EmployeeResponse.from(employee);
    }

    @Transactional
    public EmployeeResponse changeStatus(Long id, UpdateEmployeeStatusRequest request, Long actorUserId) {
        Employee employee = findEmployeeOrThrow(id);
        EmploymentStatus previousStatus = employee.getEmploymentStatus();

        if (previousStatus != request.status()) {
            employee.setEmploymentStatus(request.status());
            auditService.record(
                    actorUserId, ACTION_EMPLOYEE_STATUS_CHANGED, ENTITY_EMPLOYEE, employee.getId(),
                    Map.of("from", previousStatus.name(), "to", request.status().name()));
        }

        return EmployeeResponse.from(employee);
    }

    public EmployeeResponse getOwnProfile(Long employeeId) {
        return EmployeeResponse.from(findEmployeeOrThrow(employeeId));
    }

    @Transactional
    public EmployeeResponse updateOwnProfile(Long employeeId, UpdateOwnProfileRequest request, Long actorUserId) {
        Employee employee = findEmployeeOrThrow(employeeId);

        if (!Objects.equals(employee.getPhoneNumber(), request.phoneNumber())) {
            employee.setPhoneNumber(request.phoneNumber());
            auditService.record(
                    actorUserId, ACTION_EMPLOYEE_SELF_PROFILE_UPDATED, ENTITY_EMPLOYEE, employee.getId(),
                    Map.of("fields", List.of("phoneNumber")));
        }

        return EmployeeResponse.from(employee);
    }

    private Employee findEmployeeOrThrow(Long id) {
        return employeeRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found"));
    }

    private Department findDepartmentOrThrow(Long departmentId) {
        return departmentRepository.findById(departmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Department not found"));
    }

    private static Pageable withIdTiebreaker(Pageable pageable) {
        Sort sortWithTiebreaker = pageable.getSort().and(Sort.by("id").ascending());
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sortWithTiebreaker);
    }
}
