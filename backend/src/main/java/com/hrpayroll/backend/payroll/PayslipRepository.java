package com.hrpayroll.backend.payroll;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PayslipRepository extends JpaRepository<Payslip, Long> {

    /** Single-row fetch with payrollRun/employee eagerly joined so rendering a payslip never triggers extra queries. */
    @Query("SELECT p FROM Payslip p JOIN FETCH p.payrollRun JOIN FETCH p.employee WHERE p.id = :id")
    Optional<Payslip> findWithDetailsById(@Param("id") Long id);

    /**
     * Fetch-joined + explicit countQuery, same shape as
     * LeaveRequestRepository.findByEmployeeId -- Spring Data cannot safely
     * derive a count query from a fetch-join query. Deliberately no ORDER BY
     * here; page ordering is a later service concern, not hardcoded here.
     */
    @Query(
            value = "SELECT p FROM Payslip p JOIN FETCH p.payrollRun JOIN FETCH p.employee WHERE p.payrollRun.id = :payrollRunId",
            countQuery = "SELECT COUNT(p) FROM Payslip p WHERE p.payrollRun.id = :payrollRunId")
    Page<Payslip> findByPayrollRunId(@Param("payrollRunId") Long payrollRunId, Pageable pageable);

    @Query(
            value = "SELECT p FROM Payslip p JOIN FETCH p.payrollRun JOIN FETCH p.employee WHERE p.employee.id = :employeeId",
            countQuery = "SELECT COUNT(p) FROM Payslip p WHERE p.employee.id = :employeeId")
    Page<Payslip> findByEmployeeId(@Param("employeeId") Long employeeId, Pageable pageable);

    /**
     * Deterministic, unpaginated list of every payslip belonging to one run, for the internal PDF
     * generation batch (Phase 8C-2B1) -- no fetch join, since the orchestration never touches
     * {@code payrollRun}/{@code employee} (it already holds the locked {@code PayrollRun} directly,
     * and PDF rendering uses only Payslip's own stored snapshot/monetary columns), so there is no
     * lazy association to trigger an N+1 query for in the first place.
     */
    List<Payslip> findByPayrollRunIdOrderByIdAsc(Long payrollRunId);
}
