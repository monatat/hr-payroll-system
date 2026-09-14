package com.hrpayroll.backend.leave;

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
import java.time.LocalDateTime;

/**
 * Maps the existing V6 {@code leave_balances} table. Balances are managed
 * manually in v1 (no create/update API in Phase 7) -- rows are provisioned
 * directly (psql, or test fixtures). {@code usedDays} is only ever mutated
 * through {@link #incrementUsedDays}, called by
 * {@code LeaveRequestService.approve} after it has already verified
 * sufficiency under the balance row's pessimistic lock -- there is no
 * generic setter, and this method performs no validation of its own since
 * the caller is required to have already checked it.
 */
@Entity
@Table(name = "leave_balances")
public class LeaveBalance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "leave_type_id", nullable = false)
    private LeaveType leaveType;

    @Column(nullable = false)
    private Integer year;

    @Column(name = "entitled_days", nullable = false, precision = 5, scale = 1)
    private BigDecimal entitledDays;

    @Column(name = "used_days", nullable = false, precision = 5, scale = 1)
    private BigDecimal usedDays;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    protected LeaveBalance() {
        // for JPA
    }

    public void incrementUsedDays(BigDecimal amount) {
        this.usedDays = this.usedDays.add(amount);
    }

    public Long getId() {
        return id;
    }

    public Employee getEmployee() {
        return employee;
    }

    public LeaveType getLeaveType() {
        return leaveType;
    }

    public Integer getYear() {
        return year;
    }

    public BigDecimal getEntitledDays() {
        return entitledDays;
    }

    public BigDecimal getUsedDays() {
        return usedDays;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
