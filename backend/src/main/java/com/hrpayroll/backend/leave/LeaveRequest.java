package com.hrpayroll.backend.leave;

import com.hrpayroll.backend.employee.Employee;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Maps the existing V6 {@code leave_requests} table. {@code status} and the
 * review fields are only ever mutated together, through {@link #approve}
 * or {@link #reject} -- there are deliberately no generic public setters
 * for them, so a caller can never set e.g. {@code reviewedByUserId} without
 * also transitioning status.
 */
@Entity
@Table(name = "leave_requests")
public class LeaveRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "leave_type_id", nullable = false)
    private LeaveType leaveType;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "days_requested", nullable = false, precision = 5, scale = 1)
    private BigDecimal daysRequested;

    @Enumerated(EnumType.STRING)
    @Column(name = "duration_type", nullable = false, length = 10)
    private LeaveDurationType durationType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LeaveRequestStatus status;

    @Column(length = 500)
    private String reason;

    @Column(name = "reviewed_by")
    private Long reviewedByUserId;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    protected LeaveRequest() {
        // for JPA
    }

    public LeaveRequest(
            Employee employee, LeaveType leaveType, LocalDate startDate, LocalDate endDate, BigDecimal daysRequested,
            String reason, LeaveDurationType durationType) {
        this.employee = employee;
        this.leaveType = leaveType;
        this.startDate = startDate;
        this.endDate = endDate;
        this.daysRequested = daysRequested;
        this.reason = reason;
        this.durationType = durationType;
        this.status = LeaveRequestStatus.PENDING;
    }

    /**
     * Transitions PENDING -> APPROVED. Throws if the request is not
     * currently PENDING -- a defensive invariant check; the service layer
     * is expected to check status first and translate that case into
     * {@code LeaveRequestAlreadyProcessedException} before ever calling
     * this, so this branch should be unreachable in normal operation.
     */
    public void approve(Long reviewerUserId, LocalDateTime reviewedAt) {
        if (status != LeaveRequestStatus.PENDING) {
            throw new IllegalStateException("Only a PENDING leave request can be approved.");
        }
        this.status = LeaveRequestStatus.APPROVED;
        this.reviewedByUserId = reviewerUserId;
        this.reviewedAt = reviewedAt;
        this.rejectionReason = null;
    }

    /** Transitions PENDING -> REJECTED. Same invariant/defensive-check note as {@link #approve}. */
    public void reject(Long reviewerUserId, LocalDateTime reviewedAt, String rejectionReason) {
        if (status != LeaveRequestStatus.PENDING) {
            throw new IllegalStateException("Only a PENDING leave request can be rejected.");
        }
        this.status = LeaveRequestStatus.REJECTED;
        this.reviewedByUserId = reviewerUserId;
        this.reviewedAt = reviewedAt;
        this.rejectionReason = rejectionReason;
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

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public BigDecimal getDaysRequested() {
        return daysRequested;
    }

    public LeaveDurationType getDurationType() {
        return durationType;
    }

    public LeaveRequestStatus getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public Long getReviewedByUserId() {
        return reviewedByUserId;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public Long getVersion() {
        return version;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
