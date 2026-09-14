package com.hrpayroll.backend.leave;

import java.math.BigDecimal;

/**
 * {@code remainingDays} is computed here (entitledDays - usedDays) and
 * never stored -- BigDecimal#subtract keeps both operands' scale (both
 * NUMERIC(5,1) -> scale 1), so no explicit rounding or double conversion is
 * involved.
 */
public record LeaveBalanceResponse(
        Long id,
        Long employeeId,
        Long leaveTypeId,
        String leaveTypeName,
        Integer year,
        BigDecimal entitledDays,
        BigDecimal usedDays,
        BigDecimal remainingDays) {

    public static LeaveBalanceResponse from(LeaveBalance leaveBalance) {
        return new LeaveBalanceResponse(
                leaveBalance.getId(),
                leaveBalance.getEmployee().getId(),
                leaveBalance.getLeaveType().getId(),
                leaveBalance.getLeaveType().getName(),
                leaveBalance.getYear(),
                leaveBalance.getEntitledDays(),
                leaveBalance.getUsedDays(),
                leaveBalance.getEntitledDays().subtract(leaveBalance.getUsedDays()));
    }
}
