package com.hrpayroll.backend.leave;

import com.hrpayroll.backend.employee.EmployeeRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Phase 7A: read-only leave reference data and balance access. Leave
 * request creation/listing/approval/rejection (Phase 7B) will live in a
 * separate {@code LeaveRequestService} once that behavior is implemented,
 * to keep this class scoped to what it actually does today.
 */
@Service
@Transactional(readOnly = true)
public class LeaveService {

    private final LeaveTypeRepository leaveTypeRepository;
    private final LeaveBalanceRepository leaveBalanceRepository;
    private final EmployeeRepository employeeRepository;

    public LeaveService(
            LeaveTypeRepository leaveTypeRepository,
            LeaveBalanceRepository leaveBalanceRepository,
            EmployeeRepository employeeRepository) {
        this.leaveTypeRepository = leaveTypeRepository;
        this.leaveBalanceRepository = leaveBalanceRepository;
        this.employeeRepository = employeeRepository;
    }

    public List<LeaveTypeResponse> listLeaveTypes() {
        return leaveTypeRepository.findAllByOrderByNameAsc().stream()
                .map(LeaveTypeResponse::from)
                .toList();
    }

    /**
     * Shared by both the HR_ADMIN per-employee endpoint and the EMPLOYEE
     * self-service endpoint -- mirrors SalaryStructureService.listForEmployee
     * (Phase 6), including its defensive 404 on an unknown employeeId.
     */
    public List<LeaveBalanceResponse> getBalancesForEmployee(Long employeeId) {
        findEmployeeOrThrow(employeeId);
        return leaveBalanceRepository.findByEmployeeIdOrderByYearDescLeaveType_NameAsc(employeeId).stream()
                .map(LeaveBalanceResponse::from)
                .toList();
    }

    private void findEmployeeOrThrow(Long id) {
        if (!employeeRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found");
        }
    }
}
