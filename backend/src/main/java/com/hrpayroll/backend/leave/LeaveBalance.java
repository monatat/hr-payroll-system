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
 * directly (psql, test fixtures, or the validated constructor below, used by
 * the opt-in local demo-data profile). {@code usedDays} is only ever mutated
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

    /**
     * Creates a new balance in a schema-valid, semantically valid state. The schema itself has no
     * CHECK constraints for these invariants, so they are enforced here: both references required,
     * a year within the project's established 2000-2100 range, and non-negative day values with used
     * never above entitled. (Approval-time sufficiency remains {@code LeaveRequestService}'s job.)
     */
    public LeaveBalance(Employee employee, LeaveType leaveType, int year, BigDecimal entitledDays, BigDecimal usedDays) {
        if (employee == null) {
            throw new IllegalArgumentException("employee must not be null");
        }
        if (leaveType == null) {
            throw new IllegalArgumentException("leaveType must not be null");
        }
        if (year < 2000 || year > 2100) {
            throw new IllegalArgumentException("year must be between 2000 and 2100");
        }
        if (entitledDays == null || usedDays == null) {
            throw new IllegalArgumentException("entitledDays and usedDays must not be null");
        }
        if (entitledDays.signum() < 0 || usedDays.signum() < 0) {
            throw new IllegalArgumentException("entitledDays and usedDays must not be negative");
        }
        if (usedDays.compareTo(entitledDays) > 0) {
            throw new IllegalArgumentException("usedDays must not exceed entitledDays");
        }
        this.employee = employee;
        this.leaveType = leaveType;
        this.year = year;
        this.entitledDays = entitledDays;
        this.usedDays = usedDays;
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
