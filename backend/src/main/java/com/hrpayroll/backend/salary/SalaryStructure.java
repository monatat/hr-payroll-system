package com.hrpayroll.backend.salary;

import com.hrpayroll.backend.employee.Employee;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Maps the existing V5 {@code salary_structures} table. Append-oriented by
 * design: {@code effectiveTo} is the only field ever mutated after creation,
 * and only by {@link SalaryStructureService}'s controlled succession
 * transition (closing the previous open-ended structure when a new one
 * supersedes it) -- never via a general update endpoint.
 */
@Entity
@Table(name = "salary_structures")
public class SalaryStructure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(name = "base_pay", nullable = false, precision = 12, scale = 2)
    private BigDecimal basePay;

    @Column(name = "housing_allowance", nullable = false, precision = 12, scale = 2)
    private BigDecimal housingAllowance;

    @Column(name = "transport_allowance", nullable = false, precision = 12, scale = 2)
    private BigDecimal transportAllowance;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    protected SalaryStructure() {
        // for JPA
    }

    public SalaryStructure(
            Employee employee,
            BigDecimal basePay,
            BigDecimal housingAllowance,
            BigDecimal transportAllowance,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {
        this.employee = employee;
        this.basePay = basePay;
        this.housingAllowance = housingAllowance;
        this.transportAllowance = transportAllowance;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
    }

    public Long getId() {
        return id;
    }

    public Employee getEmployee() {
        return employee;
    }

    public BigDecimal getBasePay() {
        return basePay;
    }

    public BigDecimal getHousingAllowance() {
        return housingAllowance;
    }

    public BigDecimal getTransportAllowance() {
        return transportAllowance;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    public void setEffectiveTo(LocalDate effectiveTo) {
        this.effectiveTo = effectiveTo;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
