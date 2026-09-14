CREATE TABLE leave_types (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(50) NOT NULL,
    default_annual_entitlement_days NUMERIC(5,1) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_leave_types_name UNIQUE (name)
);

CREATE TABLE leave_balances (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id BIGINT NOT NULL,
    leave_type_id BIGINT NOT NULL,
    year INTEGER NOT NULL,
    entitled_days NUMERIC(5,1) NOT NULL,
    used_days NUMERIC(5,1) NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT fk_leave_balances_employee FOREIGN KEY (employee_id)
        REFERENCES employees (id) ON DELETE RESTRICT,
    CONSTRAINT fk_leave_balances_leave_type FOREIGN KEY (leave_type_id)
        REFERENCES leave_types (id) ON DELETE RESTRICT,
    CONSTRAINT uq_leave_balances_employee_type_year UNIQUE (employee_id, leave_type_id, year)
);

CREATE TABLE leave_requests (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id BIGINT NOT NULL,
    leave_type_id BIGINT NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    days_requested NUMERIC(5,1) NOT NULL,
    status VARCHAR(20) NOT NULL,
    reason VARCHAR(500),
    reviewed_by BIGINT,
    reviewed_at TIMESTAMP,
    rejection_reason VARCHAR(500),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT fk_leave_requests_employee FOREIGN KEY (employee_id)
        REFERENCES employees (id) ON DELETE RESTRICT,
    CONSTRAINT fk_leave_requests_leave_type FOREIGN KEY (leave_type_id)
        REFERENCES leave_types (id) ON DELETE RESTRICT,
    CONSTRAINT fk_leave_requests_reviewed_by FOREIGN KEY (reviewed_by)
        REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT chk_leave_requests_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT chk_leave_requests_date_order CHECK (end_date >= start_date)
    -- start_date/end_date are inclusive; days_requested is always computed
    -- and validated by the application (Mon-Fri only), never trusted from
    -- the client. Not re-derived here to keep leave-day math out of SQL.
);

CREATE INDEX idx_leave_requests_employee_status ON leave_requests (employee_id, status);
