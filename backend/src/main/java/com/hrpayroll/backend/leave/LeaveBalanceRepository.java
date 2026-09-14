package com.hrpayroll.backend.leave;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LeaveBalanceRepository extends JpaRepository<LeaveBalance, Long> {

    List<LeaveBalance> findByEmployeeIdOrderByYearDescLeaveType_NameAsc(Long employeeId);

    /**
     * Locks the matching balance row (SELECT ... FOR UPDATE) for the
     * duration of the caller's transaction -- same pattern as
     * EmployeeRepository.findByIdForUpdate (Phase 6). Used by
     * LeaveRequestService.approve() so concurrent approvals competing for
     * the same employee/leaveType/year balance are serialized.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM LeaveBalance b WHERE b.employee.id = :employeeId AND b.leaveType.id = :leaveTypeId AND b.year = :year")
    Optional<LeaveBalance> findForUpdate(
            @Param("employeeId") Long employeeId, @Param("leaveTypeId") Long leaveTypeId, @Param("year") Integer year);
}
