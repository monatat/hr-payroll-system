-- Matches the Phase 1 approved Attendance field list exactly -- no
-- created_at/updated_at columns here, unlike other tables.
CREATE TABLE attendance (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id BIGINT NOT NULL,
    work_date DATE NOT NULL,
    clock_in_at TIMESTAMP NOT NULL,
    clock_out_at TIMESTAMP,
    CONSTRAINT fk_attendance_employee FOREIGN KEY (employee_id)
        REFERENCES employees (id) ON DELETE RESTRICT,
    CONSTRAINT uq_attendance_employee_work_date UNIQUE (employee_id, work_date),
    CONSTRAINT chk_attendance_clock_out_after_clock_in
        CHECK (clock_out_at IS NULL OR clock_out_at >= clock_in_at)
);
