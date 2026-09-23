package com.hrpayroll.backend.attendance;

import com.hrpayroll.backend.audit.AuditService;
import com.hrpayroll.backend.employee.Employee;
import com.hrpayroll.backend.employee.EmployeeRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Employee self-service attendance (Phase 9B-1: service/domain layer only -- no controller,
 * {@code @PreAuthorize}, or HTTP mapping exists yet). Every business date/timestamp comes from the
 * injected {@link Clock} (Asia/Kuala_Lumpur in production, a fixed/mutable test double in tests) --
 * never the host machine's default timezone, and never a caller-supplied value.
 *
 * <p>Duplicate clock-in is deliberately left to the database's own {@code
 * uq_attendance_employee_work_date} constraint: {@link #clockIn} lets the resulting {@code
 * DataIntegrityViolationException} propagate uncaught. Phase 9B-2 will translate it to a stable
 * {@code ATTENDANCE_ALREADY_CLOCKED_IN} 409 in {@code ApiExceptionHandler}, mirroring how that
 * handler already translates {@code uq_departments_name}/{@code uq_employees_employee_code}.
 */
@Service
public class AttendanceService {

    private static final String ACTION_ATTENDANCE_CLOCKED_IN = "ATTENDANCE_CLOCKED_IN";
    private static final String ACTION_ATTENDANCE_CLOCKED_OUT = "ATTENDANCE_CLOCKED_OUT";
    private static final String ENTITY_ATTENDANCE = "ATTENDANCE";

    private final AttendanceRepository attendanceRepository;
    private final EmployeeRepository employeeRepository;
    private final AuditService auditService;
    private final Clock clock;

    public AttendanceService(
            AttendanceRepository attendanceRepository,
            EmployeeRepository employeeRepository,
            AuditService auditService,
            Clock clock) {
        this.attendanceRepository = attendanceRepository;
        this.employeeRepository = employeeRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    /**
     * Inserts one row for today's business date and flushes immediately, so {@code
     * uq_attendance_employee_work_date} is evaluated before this method returns -- the database
     * constraint is the sole correctness mechanism against a duplicate/concurrent clock-in, never a
     * racy pre-check. Records {@code ATTENDANCE_CLOCKED_IN} only after the insert has actually
     * survived the flush, so a losing duplicate/concurrent request never gets a success audit.
     */
    @Transactional
    public AttendanceResponse clockIn(Long employeeId, Long actorUserId) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found"));

        LocalDate workDate = LocalDate.now(clock);
        LocalDateTime clockInAt = LocalDateTime.now(clock);

        Attendance saved = attendanceRepository.saveAndFlush(new Attendance(employee, workDate, clockInAt, null));

        auditService.record(
                actorUserId,
                ACTION_ATTENDANCE_CLOCKED_IN,
                ENTITY_ATTENDANCE,
                saved.getId(),
                Map.of("workDate", workDate.toString(), "clockInAt", clockInAt.toString()));

        return AttendanceResponse.from(saved);
    }

    /**
     * Looks up only today's business-date row for this employee (never an arbitrary older open
     * record, so a stale prior-day row is never touched), then performs the conditional {@code
     * UPDATE ... WHERE clock_out_at IS NULL}. An affected-row count of zero means a concurrent
     * request already closed it, translated to the same {@link AttendanceAlreadyClockedOutException}
     * as an already-closed row read at the start of this method.
     */
    @Transactional
    public AttendanceResponse clockOut(Long employeeId, Long actorUserId) {
        LocalDate workDate = LocalDate.now(clock);
        LocalDateTime clockOutAt = LocalDateTime.now(clock);

        Attendance today = attendanceRepository
                .findByEmployeeIdAndWorkDate(employeeId, workDate)
                .orElseThrow(AttendanceNoOpenRecordException::new);
        if (today.getClockOutAt() != null) {
            throw new AttendanceAlreadyClockedOutException();
        }

        int updated = attendanceRepository.updateClockOutIfOpen(today.getId(), clockOutAt);
        if (updated == 0) {
            throw new AttendanceAlreadyClockedOutException();
        }

        Attendance reloaded = attendanceRepository
                .findById(today.getId())
                .orElseThrow(() -> new IllegalStateException("Attendance " + today.getId() + " not found after clock-out."));

        auditService.record(
                actorUserId,
                ACTION_ATTENDANCE_CLOCKED_OUT,
                ENTITY_ATTENDANCE,
                reloaded.getId(),
                Map.of("workDate", workDate.toString(), "clockOutAt", clockOutAt.toString()));

        return AttendanceResponse.from(reloaded);
    }

    /** Always produces one explicit state -- "no record yet" is NOT_CLOCKED_IN, never an error. */
    @Transactional(readOnly = true)
    public AttendanceTodayResponse getTodayStatus(Long employeeId) {
        LocalDate workDate = LocalDate.now(clock);
        return attendanceRepository
                .findByEmployeeIdAndWorkDate(employeeId, workDate)
                .map(attendance -> new AttendanceTodayResponse(
                        attendance.getClockOutAt() == null
                                ? AttendanceTodayStatus.CLOCKED_IN
                                : AttendanceTodayStatus.CLOCKED_OUT,
                        AttendanceResponse.from(attendance)))
                .orElseGet(() -> new AttendanceTodayResponse(AttendanceTodayStatus.NOT_CLOCKED_IN, null));
    }

    @Transactional(readOnly = true)
    public PagedModel<AttendanceResponse> listHistory(Long employeeId, Pageable pageable) {
        return new PagedModel<>(
                attendanceRepository.findByEmployeeIdOrderByWorkDateDescIdDesc(employeeId, pageable).map(AttendanceResponse::from));
    }
}
