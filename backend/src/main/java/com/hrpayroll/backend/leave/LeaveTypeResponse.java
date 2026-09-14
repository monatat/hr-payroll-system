package com.hrpayroll.backend.leave;

import java.math.BigDecimal;

public record LeaveTypeResponse(Long id, String name, BigDecimal defaultAnnualEntitlementDays) {

    public static LeaveTypeResponse from(LeaveType leaveType) {
        return new LeaveTypeResponse(leaveType.getId(), leaveType.getName(), leaveType.getDefaultAnnualEntitlementDays());
    }
}
