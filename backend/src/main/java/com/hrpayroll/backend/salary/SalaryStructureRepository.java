package com.hrpayroll.backend.salary;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SalaryStructureRepository extends JpaRepository<SalaryStructure, Long> {

    List<SalaryStructure> findByEmployeeIdOrderByEffectiveFromAsc(Long employeeId);

    /**
     * Every structure individually satisfying {@link SalaryPeriodCoverage#covers}'s full-period
     * rule for one employee -- expressed here as the same condition in JPQL so the DB can filter
     * before any row reaches Java, but semantically identical to (never a second, divergent
     * algorithm from) SalaryPeriodCoverage. Returns a collection, not a single result, so the
     * caller can distinguish zero/one/more-than-one coverage deliberately: overlap prevention at
     * creation time (SalaryStructureService) makes more-than-one unreachable through the normal
     * API, but is not DB-enforced (see V5's own comment), so a directly-inserted row can still
     * produce it.
     */
    @Query(
            "SELECT s FROM SalaryStructure s WHERE s.employee.id = :employeeId "
                    + "AND s.effectiveFrom <= :periodStart AND (s.effectiveTo IS NULL OR s.effectiveTo >= :periodEnd)")
    List<SalaryStructure> findCoveringStructures(
            @Param("employeeId") Long employeeId,
            @Param("periodStart") LocalDate periodStart,
            @Param("periodEnd") LocalDate periodEnd);
}
