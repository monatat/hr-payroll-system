package com.hrpayroll.backend.attendance;

/**
 * Always-present current-business-date status. {@code attendance} is null only for
 * {@link AttendanceTodayStatus#NOT_CLOCKED_IN}. The future Phase 9B-2 endpoint returns this DTO
 * with HTTP 200 for all three states -- "no record yet" is a normal, not an error, response.
 */
public record AttendanceTodayResponse(AttendanceTodayStatus state, AttendanceResponse attendance) {
}
