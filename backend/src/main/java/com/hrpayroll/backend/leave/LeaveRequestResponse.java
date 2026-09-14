package com.hrpayroll.backend.leave;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record LeaveRequestResponse(
        Long id,
        Long employeeId,
        String employeeCode,
        String employeeFullName,
        Long leaveTypeId,
        String leaveTypeName,
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal daysRequested,
        LeaveRequestStatus status,
        String reason,
        Long reviewedByUserId,
        LocalDateTime reviewedAt,
        String rejectionReason,
        LeaveDurationType durationType) {

    public static LeaveRequestResponse from(LeaveRequest leaveRequest) {
        return new LeaveRequestResponse(
                leaveRequest.getId(),
                leaveRequest.getEmployee().getId(),
                leaveRequest.getEmployee().getEmployeeCode(),
                leaveRequest.getEmployee().getFullName(),
                leaveRequest.getLeaveType().getId(),
                leaveRequest.getLeaveType().getName(),
                leaveRequest.getStartDate(),
                leaveRequest.getEndDate(),
                leaveRequest.getDaysRequested(),
                leaveRequest.getStatus(),
                leaveRequest.getReason(),
                leaveRequest.getReviewedByUserId(),
                leaveRequest.getReviewedAt(),
                leaveRequest.getRejectionReason(),
                leaveRequest.getDurationType());
    }
}
