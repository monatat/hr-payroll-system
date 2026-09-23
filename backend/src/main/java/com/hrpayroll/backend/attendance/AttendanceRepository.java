package com.hrpayroll.backend.attendance;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence contract for Phase 9. Never takes a caller-supplied employee id from a browser --
 * JWT-derived identity resolution belongs to the Phase 9B-2 controller. History ordering is baked
 * into {@link #findByEmployeeIdOrderByWorkDateDescIdDesc} rather than left to a caller-supplied
 * {@code Sort} (unlike, e.g., {@code PayslipRepository}'s plain {@code findByEmployeeId}):
 * attendance history has exactly one correct semantic order, so there is no reason for a future
 * caller to override it.
 */
public interface AttendanceRepository extends JpaRepository<Attendance, Long> {

    Optional<Attendance> findByEmployeeIdAndWorkDate(Long employeeId, LocalDate workDate);

    Page<Attendance> findByEmployeeIdOrderByWorkDateDescIdDesc(Long employeeId, Pageable pageable);

    /**
     * The sole correctness mechanism against a concurrent double clock-out: the {@code WHERE
     * a.clockOutAt IS NULL} clause is re-evaluated by PostgreSQL against the current committed row
     * at write time, so of two concurrent calls for the same row, exactly one affects a row (this
     * method returns 1) and the other affects none (returns 0) -- never both. {@code
     * clearAutomatically} evicts the persistence context afterward so a subsequent {@code findBy*}
     * call in the same transaction re-reads the fresh row from the database rather than returning a
     * stale pre-update instance from the first-level cache. Only ever called with a server-derived
     * id/timestamp -- never a browser-supplied value.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Attendance a SET a.clockOutAt = :clockOutAt WHERE a.id = :id AND a.clockOutAt IS NULL")
    int updateClockOutIfOpen(@Param("id") Long id, @Param("clockOutAt") LocalDateTime clockOutAt);
}
