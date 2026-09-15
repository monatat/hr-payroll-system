package com.hrpayroll.backend.payroll;

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
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Maps the existing V8 {@code payslips} table exactly. Every *_snapshot and
 * monetary field is set once, from a {@link PayrollCalculationResult} and
 * the employee/department state at calculation time, and never recalculated
 * or re-read from current Employee/Department/SalaryStructure state
 * afterward. No {@code @Version} column: none is approved/migrated for
 * {@code payslips} -- the future Phase 8C-2B orchestration relies on the
 * {@code payroll_runs} row lock for the whole PDF-generation batch instead
 * (see {@link #markPdfGenerated(String)}/{@link #markPdfFailed()}). {@code
 * pdfPath}/{@code pdfStatus} are only ever mutated together through those
 * two narrow lifecycle methods -- there is no generic setter for either.
 */
@Entity
@Table(name = "payslips")
public class Payslip {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payroll_run_id", nullable = false)
    private PayrollRun payrollRun;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(name = "employee_code_snapshot", nullable = false, length = 30)
    private String employeeCodeSnapshot;

    @Column(name = "employee_name_snapshot", nullable = false, length = 150)
    private String employeeNameSnapshot;

    @Column(name = "department_name_snapshot", nullable = false, length = 150)
    private String departmentNameSnapshot;

    @Column(name = "job_title_snapshot", nullable = false, length = 100)
    private String jobTitleSnapshot;

    @Column(name = "base_pay", nullable = false, precision = 12, scale = 2)
    private BigDecimal basePay;

    @Column(name = "housing_allowance", nullable = false, precision = 12, scale = 2)
    private BigDecimal housingAllowance;

    @Column(name = "transport_allowance", nullable = false, precision = 12, scale = 2)
    private BigDecimal transportAllowance;

    @Column(name = "gross_pay", nullable = false, precision = 12, scale = 2)
    private BigDecimal grossPay;

    @Column(name = "epf_deduction", nullable = false, precision = 12, scale = 2)
    private BigDecimal epfDeduction;

    @Column(name = "socso_deduction", nullable = false, precision = 12, scale = 2)
    private BigDecimal socsoDeduction;

    @Column(name = "eis_deduction", nullable = false, precision = 12, scale = 2)
    private BigDecimal eisDeduction;

    @Column(name = "income_tax", nullable = false, precision = 12, scale = 2)
    private BigDecimal incomeTax;

    @Column(name = "total_deductions", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalDeductions;

    @Column(name = "net_pay", nullable = false, precision = 12, scale = 2)
    private BigDecimal netPay;

    @Column(name = "pdf_path", length = 500)
    private String pdfPath;

    @Enumerated(EnumType.STRING)
    @Column(name = "pdf_status", nullable = false, length = 20)
    private PdfStatus pdfStatus;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    protected Payslip() {
        // for JPA
    }

    public Payslip(
            PayrollRun payrollRun,
            Employee employee,
            String employeeCodeSnapshot,
            String employeeNameSnapshot,
            String departmentNameSnapshot,
            String jobTitleSnapshot,
            BigDecimal basePay,
            BigDecimal housingAllowance,
            BigDecimal transportAllowance,
            BigDecimal grossPay,
            BigDecimal epfDeduction,
            BigDecimal socsoDeduction,
            BigDecimal eisDeduction,
            BigDecimal incomeTax,
            BigDecimal totalDeductions,
            BigDecimal netPay,
            String pdfPath,
            PdfStatus pdfStatus) {
        this.payrollRun = payrollRun;
        this.employee = employee;
        this.employeeCodeSnapshot = employeeCodeSnapshot;
        this.employeeNameSnapshot = employeeNameSnapshot;
        this.departmentNameSnapshot = departmentNameSnapshot;
        this.jobTitleSnapshot = jobTitleSnapshot;
        this.basePay = basePay;
        this.housingAllowance = housingAllowance;
        this.transportAllowance = transportAllowance;
        this.grossPay = grossPay;
        this.epfDeduction = epfDeduction;
        this.socsoDeduction = socsoDeduction;
        this.eisDeduction = eisDeduction;
        this.incomeTax = incomeTax;
        this.totalDeductions = totalDeductions;
        this.netPay = netPay;
        this.pdfPath = pdfPath;
        this.pdfStatus = pdfStatus;
    }

    /**
     * Transitions PENDING or FAILED -> GENERATED, setting {@code pdfStatus} and {@code pdfPath}
     * together. Rejected from GENERATED (defensive: the future 8C-2B orchestration always skips an
     * already-GENERATED payslip rather than calling this again, so this branch is expected
     * unreachable in normal operation -- same style as {@link PayrollRun}'s lifecycle methods).
     * {@code relativePath} must be the storage-service-computed relative path only, never an
     * absolute path or client-supplied value.
     */
    public void markPdfGenerated(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("relativePath must not be null or blank.");
        }
        if (pdfStatus == PdfStatus.GENERATED) {
            throw new IllegalStateException("A payslip already marked GENERATED cannot be marked GENERATED again.");
        }
        this.pdfStatus = PdfStatus.GENERATED;
        this.pdfPath = relativePath;
    }

    /**
     * Transitions PENDING or FAILED -> FAILED, clearing {@code pdfPath} so a FAILED row never
     * retains a usable-looking path from an earlier attempt. Rejected from GENERATED, same
     * defensive-invariant reasoning as {@link #markPdfGenerated(String)}.
     */
    public void markPdfFailed() {
        if (pdfStatus == PdfStatus.GENERATED) {
            throw new IllegalStateException("A payslip already marked GENERATED cannot be marked FAILED.");
        }
        this.pdfStatus = PdfStatus.FAILED;
        this.pdfPath = null;
    }

    public Long getId() {
        return id;
    }

    public PayrollRun getPayrollRun() {
        return payrollRun;
    }

    public Employee getEmployee() {
        return employee;
    }

    public String getEmployeeCodeSnapshot() {
        return employeeCodeSnapshot;
    }

    public String getEmployeeNameSnapshot() {
        return employeeNameSnapshot;
    }

    public String getDepartmentNameSnapshot() {
        return departmentNameSnapshot;
    }

    public String getJobTitleSnapshot() {
        return jobTitleSnapshot;
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

    public BigDecimal getGrossPay() {
        return grossPay;
    }

    public BigDecimal getEpfDeduction() {
        return epfDeduction;
    }

    public BigDecimal getSocsoDeduction() {
        return socsoDeduction;
    }

    public BigDecimal getEisDeduction() {
        return eisDeduction;
    }

    public BigDecimal getIncomeTax() {
        return incomeTax;
    }

    public BigDecimal getTotalDeductions() {
        return totalDeductions;
    }

    public BigDecimal getNetPay() {
        return netPay;
    }

    public String getPdfPath() {
        return pdfPath;
    }

    public PdfStatus getPdfStatus() {
        return pdfStatus;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
