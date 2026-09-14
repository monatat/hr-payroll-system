package com.hrpayroll.backend.employee;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    /**
     * Locks the employee row (SELECT ... FOR UPDATE) for the duration of the
     * caller's transaction. Used by SalaryStructureService.create() so that
     * concurrent salary writes for the same employee are serialized -- see
     * the V5 salary_structures migration's own comment on this design.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Employee e WHERE e.id = :id")
    Optional<Employee> findByIdForUpdate(@Param("id") Long id);

    /**
     * Loads and locks (SELECT ... FOR UPDATE) every payroll candidate for a period -- ACTIVE and
     * hired on/before periodStart -- in the deterministic employeeCode ASC, id ASC order the
     * payroll calculation transaction requires. Locking these rows here, using the same
     * PESSIMISTIC_WRITE convention as findByIdForUpdate, protects against a concurrent salary
     * structure change: SalaryStructureService.create() locks the same employee row first, so it
     * cannot proceed while a payroll calculation holds this lock, and vice versa. Deliberately does
     * not filter by salary-structure existence -- candidate eligibility and salary coverage are
     * separate rules (coverage is resolved afterward, per candidate).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "SELECT e FROM Employee e WHERE e.employmentStatus = :status AND e.hireDate <= :periodStart "
                    + "ORDER BY e.employeeCode ASC, e.id ASC")
    List<Employee> findPayrollCandidatesForUpdate(
            @Param("status") EmploymentStatus status, @Param("periodStart") LocalDate periodStart);

    /**
     * Single JPQL query covering optional free-text search (name/code) plus
     * optional department/status filters -- deliberately not a
     * Specification/Criteria setup, per the approved "don't overengineer"
     * scope. JOIN FETCH on the (ManyToOne) department avoids N+1 without
     * row multiplication, so an explicit countQuery is supplied since
     * Spring Data cannot safely derive one from a fetch-join query.
     */
    // The LIKE pattern (already lower-cased, with '%' wrapping applied) is
    // pre-computed in the service layer rather than built via CONCAT() here:
    // Hibernate/PostgreSQL cannot reliably infer the bind parameter's type
    // for a nullable String used only inside CONCAT(), and defaults to
    // "bytea", making LOWER() fail at runtime for a null search term.
    @Query(
            value = """
                    SELECT e FROM Employee e JOIN FETCH e.department d
                    WHERE (:pattern IS NULL OR LOWER(e.fullName) LIKE :pattern OR LOWER(e.employeeCode) LIKE :pattern)
                    AND (:departmentId IS NULL OR d.id = :departmentId)
                    AND (:status IS NULL OR e.employmentStatus = :status)
                    """,
            countQuery = """
                    SELECT COUNT(e) FROM Employee e
                    WHERE (:pattern IS NULL OR LOWER(e.fullName) LIKE :pattern OR LOWER(e.employeeCode) LIKE :pattern)
                    AND (:departmentId IS NULL OR e.department.id = :departmentId)
                    AND (:status IS NULL OR e.employmentStatus = :status)
                    """)
    Page<Employee> search(
            @Param("pattern") String pattern,
            @Param("departmentId") Long departmentId,
            @Param("status") EmploymentStatus status,
            Pageable pageable);
}
