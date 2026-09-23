package com.hrpayroll.backend.attendance;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Employee-facing attendance DTO. Deliberately excludes {@code employeeId}/the {@code Employee}
 * relationship, any user id, department, and salary/payroll information -- the future Phase 9B-2
 * controller derives ownership only from the authenticated JWT, so no identity field belongs here.
 */
public record AttendanceResponse(Long id, LocalDate workDate, LocalDateTime clockInAt, LocalDateTime clockOutAt) {

    public static AttendanceResponse from(Attendance attendance) {
        return new AttendanceResponse(
                attendance.getId(), attendance.getWorkDate(), attendance.getClockInAt(), attendance.getClockOutAt());
    }
}
