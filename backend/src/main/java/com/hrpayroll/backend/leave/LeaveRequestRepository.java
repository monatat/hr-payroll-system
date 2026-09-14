package com.hrpayroll.backend.leave;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, Long> {

    /** Single-row fetch with employee/leaveType eagerly joined so the response mapper never triggers extra queries. */
    @Query("SELECT r FROM LeaveRequest r JOIN FETCH r.employee JOIN FETCH r.leaveType WHERE r.id = :id")
    Optional<LeaveRequest> findWithDetailsById(@Param("id") Long id);

    @Query(
            value = "SELECT r FROM LeaveRequest r JOIN FETCH r.employee JOIN FETCH r.leaveType WHERE r.employee.id = :employeeId",
            countQuery = "SELECT COUNT(r) FROM LeaveRequest r WHERE r.employee.id = :employeeId")
    Page<LeaveRequest> findByEmployeeId(@Param("employeeId") Long employeeId, Pageable pageable);

    /**
     * Admin listing with optional status/employeeId filters -- same shape
     * as EmployeeRepository.search: a fetch-joined main query plus an
     * explicit countQuery, since Spring Data cannot safely derive a count
     * query from a fetch-join query.
     */
    @Query(
            value = """
                    SELECT r FROM LeaveRequest r JOIN FETCH r.employee e JOIN FETCH r.leaveType
                    WHERE (:status IS NULL OR r.status = :status)
                    AND (:employeeId IS NULL OR e.id = :employeeId)
                    """,
            countQuery = """
                    SELECT COUNT(r) FROM LeaveRequest r
                    WHERE (:status IS NULL OR r.status = :status)
                    AND (:employeeId IS NULL OR r.employee.id = :employeeId)
                    """)
    Page<LeaveRequest> search(
            @Param("status") LeaveRequestStatus status, @Param("employeeId") Long employeeId, Pageable pageable);

    /**
     * Counts (rather than loads) PENDING/APPROVED requests for the employee
     * whose [startDate, endDate] overlaps the proposed range -- overlap
     * applies across all leave types, so leaveTypeId is deliberately not a
     * filter here. The interval check itself (start/end comparisons) is
     * evaluated by PostgreSQL, not in application code.
     */
    @Query("""
            SELECT COUNT(r) FROM LeaveRequest r
            WHERE r.employee.id = :employeeId
            AND r.status IN :statuses
            AND r.startDate <= :endDate
            AND r.endDate >= :startDate
            """)
    long countOverlapping(
            @Param("employeeId") Long employeeId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("statuses") List<LeaveRequestStatus> statuses);
}
