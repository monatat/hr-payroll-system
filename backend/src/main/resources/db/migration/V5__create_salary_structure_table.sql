CREATE TABLE salary_structures (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id BIGINT NOT NULL,
    base_pay NUMERIC(12,2) NOT NULL,
    housing_allowance NUMERIC(12,2) NOT NULL DEFAULT 0,
    transport_allowance NUMERIC(12,2) NOT NULL DEFAULT 0,
    effective_from DATE NOT NULL,
    effective_to DATE,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT fk_salary_structures_employee FOREIGN KEY (employee_id)
        REFERENCES employees (id) ON DELETE RESTRICT,
    CONSTRAINT chk_salary_structures_date_order
        CHECK (effective_to IS NULL OR effective_to >= effective_from),
    -- Overlap between two salary structures for the same employee is a
    -- multi-row invariant and is intentionally NOT enforced here (no
    -- exclusion constraint). It is protected at the service layer via an
    -- Employee row lock (SELECT ... FOR UPDATE) plus overlap validation.
    CONSTRAINT chk_salary_structures_base_pay_non_negative CHECK (base_pay >= 0),
    CONSTRAINT chk_salary_structures_housing_allowance_non_negative CHECK (housing_allowance >= 0),
    CONSTRAINT chk_salary_structures_transport_allowance_non_negative CHECK (transport_allowance >= 0)
);

CREATE INDEX idx_salary_structures_employee_effective_from
    ON salary_structures (employee_id, effective_from);
