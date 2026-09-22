package com.hrpayroll.backend.attendance;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Foundation-only persistence contract for Phase 9A. Never takes a caller-supplied employee id
 * from a browser -- JWT-derived identity resolution belongs to the future Phase 9B controller.
 * History ordering is baked into {@link #findByEmployeeIdOrderByWorkDateDescIdDesc} rather than
 * left to a caller-supplied {@code Sort} (unlike, e.g., {@code PayslipRepository}'s plain {@code
 * findByEmployeeId}): attendance history has exactly one correct semantic order, so there is no
 * reason for a future caller to override it.
 */
public interface AttendanceRepository extends JpaRepository<Attendance, Long> {

    Optional<Attendance> findByEmployeeIdAndWorkDate(Long employeeId, LocalDate workDate);

    Page<Attendance> findByEmployeeIdOrderByWorkDateDescIdDesc(Long employeeId, Pageable pageable);
}
