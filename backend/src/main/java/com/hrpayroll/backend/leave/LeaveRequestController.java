package com.hrpayroll.backend.leave;

import com.hrpayroll.backend.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * POST (create, EMPLOYEE) and the two GET (list, HR_ADMIN; detail, either
 * role) endpoints share the same {@code /api/leave-requests} resource path,
 * which is why they live in one controller rather than being split by
 * package the way {@code /api/employees}/{@code /api/employees/me} are --
 * each method still carries its own explicit authorization. Self-service
 * history (`/me`) is a distinct path and lives in
 * {@link LeaveRequestSelfServiceController}.
 */
@RestController
@RequestMapping("/api/leave-requests")
public class LeaveRequestController {

    private final LeaveRequestService leaveRequestService;

    public LeaveRequestController(LeaveRequestService leaveRequestService) {
        this.leaveRequestService = leaveRequestService;
    }

    @PostMapping
    @PreAuthorize("hasRole('EMPLOYEE')")
    public ResponseEntity<LeaveRequestResponse> create(
            @Valid @RequestBody CreateLeaveRequestRequest request, @AuthenticationPrincipal AuthenticatedUser principal) {
        LeaveRequestResponse created = leaveRequestService.create(requireEmployeeId(principal), request, principal.userId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/leave-requests/" + created.id()))
                .body(created);
    }

    @GetMapping
    @PreAuthorize("hasRole('HR_ADMIN')")
    public PagedModel<LeaveRequestResponse> listForAdmin(
            @RequestParam(required = false) LeaveRequestStatus status,
            @RequestParam(required = false) Long employeeId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return leaveRequestService.listForAdmin(status, employeeId, pageable);
    }

    @GetMapping("/{id}")
    public LeaveRequestResponse getById(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser principal) {
        return leaveRequestService.getById(id, principal);
    }

    @PutMapping("/{id}/approve")
    @PreAuthorize("hasRole('HR_ADMIN')")
    public LeaveRequestResponse approve(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser principal) {
        return leaveRequestService.approve(id, principal.userId());
    }

    @PutMapping("/{id}/reject")
    @PreAuthorize("hasRole('HR_ADMIN')")
    public LeaveRequestResponse reject(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) RejectLeaveRequestRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        String reason = request != null ? request.reason() : null;
        return leaveRequestService.reject(id, reason, principal.userId());
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
