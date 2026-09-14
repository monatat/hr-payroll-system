package com.hrpayroll.backend.leave;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only reference data, available to any authenticated role (no
 * {@code @PreAuthorize}) -- same pattern as {@code DepartmentController}'s
 * GET endpoint. {@code SecurityConfig} already requires authentication for
 * every non-permitted request.
 */
@RestController
@RequestMapping("/api/leave-types")
public class LeaveTypeController {

    private final LeaveService leaveService;

    public LeaveTypeController(LeaveService leaveService) {
        this.leaveService = leaveService;
    }

    @GetMapping
    public List<LeaveTypeResponse> list() {
        return leaveService.listLeaveTypes();
    }
}
