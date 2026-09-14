package com.hrpayroll.backend.payroll;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PayrollRunRepository extends JpaRepository<PayrollRun, Long> {

    Optional<PayrollRun> findByPeriodMonthAndPeriodYear(Integer periodMonth, Integer periodYear);

    boolean existsByPeriodMonthAndPeriodYear(Integer periodMonth, Integer periodYear);

    /**
     * Admin listing with optional status/year filters -- same shape as
     * EmployeeRepository.search/LeaveRequestRepository.search: an explicit countQuery, since
     * PayrollRun has no association to fetch-join here (unlike those two), but the project's
     * established optional-filter-in-one-query pattern is kept for consistency. No ORDER BY here;
     * PayrollQueryService supplies createdAt DESC plus an id DESC tiebreaker via the Pageable.
     */
    @Query(
            value = "SELECT r FROM PayrollRun r WHERE (:status IS NULL OR r.status = :status) AND (:year IS NULL OR r.periodYear = :year)",
            countQuery =
                    "SELECT COUNT(r) FROM PayrollRun r WHERE (:status IS NULL OR r.status = :status) AND (:year IS NULL OR r.periodYear = :year)")
    Page<PayrollRun> search(@Param("status") PayrollRunStatus status, @Param("year") Integer year, Pageable pageable);

    /**
     * Locks the run row (SELECT ... FOR UPDATE) for the duration of the
     * caller's transaction -- for the future retry path (Phase 8B), mirrors
     * EmployeeRepository.findByIdForUpdate.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM PayrollRun r WHERE r.id = :id")
    Optional<PayrollRun> findByIdForUpdate(@Param("id") Long id);
}
