package com.hrpayroll.backend.payroll;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Maps the existing V8 {@code payroll_runs} table plus V13's rate-snapshot
 * and {@code version} columns. Carries exactly four lifecycle transitions,
 * {@link #markCalculated()}, {@link #markFailed(String)},
 * {@link #prepareForRetry()}, and {@link #markCompleted()} -- there is
 * deliberately no generic status setter and no reset/cancel/delete method.
 * {@code markCompleted()} only enforces its own single precondition (must
 * currently be CALCULATED); verifying that every payslip in the run is
 * actually GENERATED first is the future Phase 8C-2B orchestration
 * service's responsibility, since the entity itself cannot query other rows.
 */
@Entity
@Table(name = "payroll_runs")
public class PayrollRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "period_month", nullable = false)
    private Integer periodMonth;

    @Column(name = "period_year", nullable = false)
    private Integer periodYear;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PayrollRunStatus status;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "epf_rate_snapshot", nullable = false, precision = 5, scale = 4)
    private BigDecimal epfRateSnapshot;

    @Column(name = "socso_rate_snapshot", nullable = false, precision = 5, scale = 4)
    private BigDecimal socsoRateSnapshot;

    @Column(name = "eis_rate_snapshot", nullable = false, precision = 5, scale = 4)
    private BigDecimal eisRateSnapshot;

    @Column(name = "income_tax_rate_snapshot", nullable = false, precision = 5, scale = 4)
    private BigDecimal incomeTaxRateSnapshot;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    protected PayrollRun() {
        // for JPA
    }

    public PayrollRun(
            Integer periodMonth,
            Integer periodYear,
            LocalDate periodStart,
            LocalDate periodEnd,
            PayrollRunStatus status,
            BigDecimal epfRateSnapshot,
            BigDecimal socsoRateSnapshot,
            BigDecimal eisRateSnapshot,
            BigDecimal incomeTaxRateSnapshot) {
        this.periodMonth = periodMonth;
        this.periodYear = periodYear;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.status = status;
        this.epfRateSnapshot = epfRateSnapshot;
        this.socsoRateSnapshot = socsoRateSnapshot;
        this.eisRateSnapshot = eisRateSnapshot;
        this.incomeTaxRateSnapshot = incomeTaxRateSnapshot;
    }

    /**
     * Transitions PROCESSING -> CALCULATED. Throws if the run is not
     * currently PROCESSING -- a defensive invariant check; the transaction
     * service is expected to check status first (under lock) before ever
     * calling this, so this branch should be unreachable in normal
     * operation.
     */
    public void markCalculated() {
        if (status != PayrollRunStatus.PROCESSING) {
            throw new IllegalStateException("Only a PROCESSING payroll run can be marked CALCULATED.");
        }
        this.status = PayrollRunStatus.CALCULATED;
    }

    /** Transitions PROCESSING -> FAILED, storing the sanitized business-level reason. Same invariant-check note as {@link #markCalculated()}. */
    public void markFailed(String safeReason) {
        if (status != PayrollRunStatus.PROCESSING) {
            throw new IllegalStateException("Only a PROCESSING payroll run can be marked FAILED.");
        }
        this.status = PayrollRunStatus.FAILED;
        this.failureReason = safeReason;
    }

    /**
     * Transitions FAILED -> PROCESSING for a retry attempt, clearing the previous failure reason.
     * Throws if the run is not currently FAILED -- the transaction service is expected to check
     * status first (under lock) before ever calling this, so this branch should be unreachable in
     * normal operation, same as {@link #markCalculated()}/{@link #markFailed(String)}.
     */
    public void prepareForRetry() {
        if (status != PayrollRunStatus.FAILED) {
            throw new IllegalStateException("Only a FAILED payroll run can be retried.");
        }
        this.status = PayrollRunStatus.PROCESSING;
        this.failureReason = null;
    }

    /**
     * Transitions CALCULATED -> COMPLETED. Throws if the run is not currently CALCULATED -- same
     * defensive-invariant pattern as the other lifecycle methods. This method only checks its own
     * status; it cannot verify that every payslip in the run is GENERATED (that requires querying
     * other rows), so the future Phase 8C-2B orchestration service must confirm that itself,
     * under the same run-row lock, before ever calling this.
     */
    public void markCompleted() {
        if (status != PayrollRunStatus.CALCULATED) {
            throw new IllegalStateException("Only a CALCULATED payroll run can be marked COMPLETED.");
        }
        this.status = PayrollRunStatus.COMPLETED;
    }

    public Long getId() {
        return id;
    }

    public Integer getPeriodMonth() {
        return periodMonth;
    }

    public Integer getPeriodYear() {
        return periodYear;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public PayrollRunStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public BigDecimal getEpfRateSnapshot() {
        return epfRateSnapshot;
    }

    public BigDecimal getSocsoRateSnapshot() {
        return socsoRateSnapshot;
    }

    public BigDecimal getEisRateSnapshot() {
        return eisRateSnapshot;
    }

    public BigDecimal getIncomeTaxRateSnapshot() {
        return incomeTaxRateSnapshot;
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
