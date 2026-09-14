CREATE TABLE payroll_runs (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    period_month INTEGER NOT NULL,
    period_year INTEGER NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    status VARCHAR(20) NOT NULL,
    failure_reason VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_payroll_runs_period UNIQUE (period_month, period_year),
    CONSTRAINT chk_payroll_runs_period_month CHECK (period_month BETWEEN 1 AND 12),
    CONSTRAINT chk_payroll_runs_period_date_order CHECK (period_end >= period_start),
    CONSTRAINT chk_payroll_runs_status
        CHECK (status IN ('PROCESSING', 'CALCULATED', 'COMPLETED', 'FAILED'))
);

CREATE INDEX idx_payroll_runs_status ON payroll_runs (status);

-- Payslips are immutable historical snapshots. The *_snapshot columns and
-- all financial figures are plain data with no FK back to employees or
-- salary_structures -- rendering a payslip never depends on current
-- Employee/SalaryStructure state.
CREATE TABLE payslips (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payroll_run_id BIGINT NOT NULL,
    employee_id BIGINT NOT NULL,
    employee_code_snapshot VARCHAR(30) NOT NULL,
    employee_name_snapshot VARCHAR(150) NOT NULL,
    department_name_snapshot VARCHAR(150) NOT NULL,
    job_title_snapshot VARCHAR(100) NOT NULL,
    base_pay NUMERIC(12,2) NOT NULL,
    housing_allowance NUMERIC(12,2) NOT NULL,
    transport_allowance NUMERIC(12,2) NOT NULL,
    gross_pay NUMERIC(12,2) NOT NULL,
    epf_deduction NUMERIC(12,2) NOT NULL,
    socso_deduction NUMERIC(12,2) NOT NULL,
    eis_deduction NUMERIC(12,2) NOT NULL,
    income_tax NUMERIC(12,2) NOT NULL,
    total_deductions NUMERIC(12,2) NOT NULL,
    net_pay NUMERIC(12,2) NOT NULL,
    pdf_path VARCHAR(500),
    pdf_status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT fk_payslips_payroll_run FOREIGN KEY (payroll_run_id)
        REFERENCES payroll_runs (id) ON DELETE RESTRICT,
    CONSTRAINT fk_payslips_employee FOREIGN KEY (employee_id)
        REFERENCES employees (id) ON DELETE RESTRICT,
    CONSTRAINT uq_payslips_payroll_run_employee UNIQUE (payroll_run_id, employee_id),
    CONSTRAINT chk_payslips_pdf_status CHECK (pdf_status IN ('PENDING', 'GENERATED', 'FAILED')),
    CONSTRAINT chk_payslips_base_pay_non_negative CHECK (base_pay >= 0),
    CONSTRAINT chk_payslips_housing_allowance_non_negative CHECK (housing_allowance >= 0),
    CONSTRAINT chk_payslips_transport_allowance_non_negative CHECK (transport_allowance >= 0),
    CONSTRAINT chk_payslips_gross_pay_non_negative CHECK (gross_pay >= 0),
    CONSTRAINT chk_payslips_epf_deduction_non_negative CHECK (epf_deduction >= 0),
    CONSTRAINT chk_payslips_socso_deduction_non_negative CHECK (socso_deduction >= 0),
    CONSTRAINT chk_payslips_eis_deduction_non_negative CHECK (eis_deduction >= 0),
    CONSTRAINT chk_payslips_income_tax_non_negative CHECK (income_tax >= 0),
    CONSTRAINT chk_payslips_total_deductions_non_negative CHECK (total_deductions >= 0),
    CONSTRAINT chk_payslips_net_pay_non_negative CHECK (net_pay >= 0)
);

CREATE INDEX idx_payslips_employee_id ON payslips (employee_id);
