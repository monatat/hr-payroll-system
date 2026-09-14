package com.hrpayroll.backend.leave;

import com.hrpayroll.backend.security.AuthenticatedUser;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * EMPLOYEE-only own leave-request history. No {employeeId} path variable --
 * identity comes only from the authenticated JWT principal, matching
 * EmployeeSelfServiceController/SalaryStructureSelfServiceController/
 * LeaveBalanceSelfServiceController.
 */
@RestController
@RequestMapping("/api/leave-requests/me")
@PreAuthorize("hasRole('EMPLOYEE')")
public class LeaveRequestSelfServiceController {

    private final LeaveRequestService leaveRequestService;

    public LeaveRequestSelfServiceController(LeaveRequestService leaveRequestService) {
        this.leaveRequestService = leaveRequestService;
    }

    @GetMapping
    public PagedModel<LeaveRequestResponse> getOwnHistory(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return leaveRequestService.listForEmployee(requireEmployeeId(principal), pageable);
    }

    private static Long requireEmployeeId(AuthenticatedUser principal) {
        Long employeeId = principal.employeeId();
        if (employeeId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee profile not found");
        }
        return employeeId;
    }
}
