package com.hrpayroll.backend.salary;

import com.hrpayroll.backend.audit.AuditService;
import com.hrpayroll.backend.employee.Employee;
import com.hrpayroll.backend.employee.EmployeeRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Append-oriented salary structure management. The only mutation of an
 * existing row is the one approved "controlled succession" transition: when
 * a new structure supersedes the employee's currently open-ended one, that
 * row's {@code effectiveTo} is closed in the same transaction, under the
 * same employee-row lock, before the new row is inserted.
 */
@Service
@Transactional(readOnly = true)
public class SalaryStructureService {

    private static final String ACTION_SALARY_STRUCTURE_CREATED = "SALARY_STRUCTURE_CREATED";
    private static final String ENTITY_SALARY_STRUCTURE = "SALARY_STRUCTURE";

    private final EmployeeRepository employeeRepository;
    private final SalaryStructureRepository salaryStructureRepository;
    private final AuditService auditService;

    public SalaryStructureService(
            EmployeeRepository employeeRepository,
            SalaryStructureRepository salaryStructureRepository,
            AuditService auditService) {
        this.employeeRepository = employeeRepository;
        this.salaryStructureRepository = salaryStructureRepository;
        this.auditService = auditService;
    }

    public List<SalaryStructureResponse> listForEmployee(Long employeeId) {
        findEmployeeOrThrow(employeeId);
        return salaryStructureRepository.findByEmployeeIdOrderByEffectiveFromAsc(employeeId).stream()
                .map(SalaryStructureResponse::from)
                .toList();
    }

    public SalaryStructureResponse getById(Long id) {
        return SalaryStructureResponse.from(findSalaryStructureOrThrow(id));
    }

    @Transactional
    public SalaryStructureResponse create(Long employeeId, CreateSalaryStructureRequest request, Long actorUserId) {
        Employee employee = employeeRepository.findByIdForUpdate(employeeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found"));

        LocalDate effectiveFrom = request.effectiveFrom();
        LocalDate effectiveTo = request.effectiveTo();
        if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "effectiveTo cannot be before effectiveFrom");
        }

        BigDecimal housingAllowance = request.housingAllowance() != null ? request.housingAllowance() : BigDecimal.ZERO;
        BigDecimal transportAllowance =
                request.transportAllowance() != null ? request.transportAllowance() : BigDecimal.ZERO;

        List<SalaryStructure> existing = salaryStructureRepository.findByEmployeeIdOrderByEffectiveFromAsc(employeeId);

        // The one row this request may supersede: the employee's current
        // open-ended structure, only if the new period genuinely starts
        // after it began. Never mutated yet -- only identified.
        SalaryStructure toSupersede = existing.stream()
                .filter(s -> s.getEffectiveTo() == null && s.getEffectiveFrom().isBefore(effectiveFrom))
                .findFirst()
                .orElse(null);
        LocalDate supersededAdjustedEffectiveTo = toSupersede != null ? effectiveFrom.minusDays(1) : null;

        for (SalaryStructure candidate : existing) {
            LocalDate candidateEffectiveTo =
                    candidate == toSupersede ? supersededAdjustedEffectiveTo : candidate.getEffectiveTo();
            if (overlaps(effectiveFrom, effectiveTo, candidate.getEffectiveFrom(), candidateEffectiveTo)) {
                throw new SalaryStructureOverlapException();
            }
        }

        // Validation passed for every existing row (using the adjusted range
        // for the superseded row) -- only now is the mutation applied.
        Long closedPreviousStructureId = null;
        if (toSupersede != null) {
            toSupersede.setEffectiveTo(supersededAdjustedEffectiveTo);
            closedPreviousStructureId = toSupersede.getId();
        }

        SalaryStructure saved = salaryStructureRepository.save(
                new SalaryStructure(employee, request.basePay(), housingAllowance, transportAllowance, effectiveFrom, effectiveTo));

        auditService.record(actorUserId, ACTION_SALARY_STRUCTURE_CREATED, ENTITY_SALARY_STRUCTURE, saved.getId(),
                auditDetails(employeeId, effectiveFrom, effectiveTo, closedPreviousStructureId));

        return SalaryStructureResponse.from(saved);
    }

    private static Map<String, Object> auditDetails(
            Long employeeId, LocalDate effectiveFrom, LocalDate effectiveTo, Long closedPreviousStructureId) {
        if (closedPreviousStructureId != null) {
            return Map.of(
                    "employeeId", employeeId,
                    "effectiveFrom", effectiveFrom.toString(),
                    "effectiveTo", effectiveTo == null ? "OPEN" : effectiveTo.toString(),
                    "closedPreviousStructureId", closedPreviousStructureId);
        }
        return Map.of(
                "employeeId", employeeId,
                "effectiveFrom", effectiveFrom.toString(),
                "effectiveTo", effectiveTo == null ? "OPEN" : effectiveTo.toString());
    }

    /**
     * Inclusive-date, null-as-infinity overlap check shared by validation
     * and (indirectly) by the future Payroll Engine's coverage rule. Two
     * ranges overlap iff each range's start is not after the other range's
     * end -- a null end is treated as +infinity.
     */
    static boolean overlaps(LocalDate aFrom, LocalDate aTo, LocalDate bFrom, LocalDate bTo) {
        boolean aStartsBeforeOrOnBEnd = bTo == null || !aFrom.isAfter(bTo);
        boolean bStartsBeforeOrOnAEnd = aTo == null || !bFrom.isAfter(aTo);
        return aStartsBeforeOrOnBEnd && bStartsBeforeOrOnAEnd;
    }

    private Employee findEmployeeOrThrow(Long id) {
        return employeeRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found"));
    }

    private SalaryStructure findSalaryStructureOrThrow(Long id) {
        return salaryStructureRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Salary structure not found"));
    }
}
