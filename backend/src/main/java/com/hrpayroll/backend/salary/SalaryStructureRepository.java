package com.hrpayroll.backend.salary;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SalaryStructureRepository extends JpaRepository<SalaryStructure, Long> {

    List<SalaryStructure> findByEmployeeIdOrderByEffectiveFromAsc(Long employeeId);
}
