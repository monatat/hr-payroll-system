package com.hrpayroll.backend.attendance;

import com.hrpayroll.backend.security.AuthenticatedUser;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * EMPLOYEE-only self-service attendance clock-in/clock-out/today/history. No {employeeId}
 * path/query/body parameter anywhere -- identity comes only from the authenticated JWT principal,
 * matching PayslipSelfServiceController/LeaveRequestSelfServiceController's existing pattern. No
 * generic ownership-comparison helper is introduced, per that same established convention.
 */
@RestController
@RequestMapping("/api/employees/me/attendance")
@PreAuthorize("hasRole('EMPLOYEE')")
public class AttendanceSelfServiceController {

    private final AttendanceService attendanceService;

    public AttendanceSelfServiceController(AttendanceService attendanceService) {
        this.attendanceService = attendanceService;
    }

    /**
     * No request body: the only input is the authenticated principal, exactly like
     * PayrollRunController's retry/generate-pdfs endpoints. Duplicate-clock-in conflicts surface as
     * a {@code DataIntegrityViolationException} from the service, translated by
     * {@code ApiExceptionHandler}'s existing constraint-name map -- never handled here.
     */
    @PostMapping("/clock-in")
    public ResponseEntity<AttendanceResponse> clockIn(@AuthenticationPrincipal AuthenticatedUser principal) {
        AttendanceResponse response = attendanceService.clockIn(requireEmployeeId(principal), principal.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /** No request body. {@link AttendanceNoOpenRecordException}/{@link AttendanceAlreadyClockedOutException} are handled by {@link AttendanceExceptionHandler}. */
    @PostMapping("/clock-out")
    public AttendanceResponse clockOut(@AuthenticationPrincipal AuthenticatedUser principal) {
        return attendanceService.clockOut(requireEmployeeId(principal), principal.userId());
    }

    /** Always 200: NOT_CLOCKED_IN/CLOCKED_IN/CLOCKED_OUT are all normal states, never an error. */
    @GetMapping("/today")
    public AttendanceTodayResponse today(@AuthenticationPrincipal AuthenticatedUser principal) {
        return attendanceService.getTodayStatus(requireEmployeeId(principal));
    }

    /**
     * {@code page}/{@code size} only -- the Pageable's own {@code Sort} (which
     * {@code PageableHandlerMethodArgumentResolver} would otherwise bind from a client-supplied
     * {@code sort} query parameter) is deliberately discarded before calling the service, so the
     * repository's baked-in workDate-DESC/id-DESC ordering remains the sole, non-overridable order.
     */
    @GetMapping
    public PagedModel<AttendanceResponse> history(
            @AuthenticationPrincipal AuthenticatedUser principal, @PageableDefault(size = 20) Pageable pageable) {
        Pageable pageOnly = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        return attendanceService.listHistory(requireEmployeeId(principal), pageOnly);
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
