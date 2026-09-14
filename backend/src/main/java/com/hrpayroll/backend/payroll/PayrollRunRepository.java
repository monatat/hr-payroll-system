package com.hrpayroll.backend.payroll;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PayrollRunRepository extends JpaRepository<PayrollRun, Long> {

    Optional<PayrollRun> findByPeriodMonthAndPeriodYear(Integer periodMonth, Integer periodYear);

    boolean existsByPeriodMonthAndPeriodYear(Integer periodMonth, Integer periodYear);

    /**
     * Locks the run row (SELECT ... FOR UPDATE) for the duration of the
     * caller's transaction -- for the future retry path (Phase 8B), mirrors
     * EmployeeRepository.findByIdForUpdate.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM PayrollRun r WHERE r.id = :id")
    Optional<PayrollRun> findByIdForUpdate(@Param("id") Long id);
}
