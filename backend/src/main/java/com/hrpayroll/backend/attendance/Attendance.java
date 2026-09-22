package com.hrpayroll.backend.attendance;

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
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Maps the existing V7 {@code attendance} table exactly -- no {@code created_at}, {@code
 * updated_at}, or {@code version} column exists on this table (unlike most others in this
 * project). Clock-in/clock-out lifecycle behavior belongs to the future Phase 9B
 * {@code AttendanceService}; this Phase 9A entity only establishes the mapping and read access.
 */
@Entity
@Table(name = "attendance")
public class Attendance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Column(name = "clock_in_at", nullable = false)
    private LocalDateTime clockInAt;

    @Column(name = "clock_out_at")
    private LocalDateTime clockOutAt;

    protected Attendance() {
        // for JPA
    }

    public Attendance(Employee employee, LocalDate workDate, LocalDateTime clockInAt, LocalDateTime clockOutAt) {
        this.employee = employee;
        this.workDate = workDate;
        this.clockInAt = clockInAt;
        this.clockOutAt = clockOutAt;
    }

    public Long getId() {
        return id;
    }

    public Employee getEmployee() {
        return employee;
    }

    public LocalDate getWorkDate() {
        return workDate;
    }

    public LocalDateTime getClockInAt() {
        return clockInAt;
    }

    public LocalDateTime getClockOutAt() {
        return clockOutAt;
    }
}
