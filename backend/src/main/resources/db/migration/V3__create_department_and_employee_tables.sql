CREATE TABLE departments (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(150) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_departments_name UNIQUE (name)
);

CREATE TABLE employees (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_code VARCHAR(30) NOT NULL,
    full_name VARCHAR(150) NOT NULL,
    ic_number VARCHAR(20) NOT NULL,
    department_id BIGINT NOT NULL,
    job_title VARCHAR(100) NOT NULL,
    employment_status VARCHAR(20) NOT NULL,
    hire_date DATE NOT NULL,
    bank_name VARCHAR(100),
    bank_account_no VARCHAR(30),
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_employees_employee_code UNIQUE (employee_code),
    CONSTRAINT uq_employees_ic_number UNIQUE (ic_number),
    CONSTRAINT fk_employees_department FOREIGN KEY (department_id)
        REFERENCES departments (id) ON DELETE RESTRICT,
    CONSTRAINT chk_employees_employment_status
        CHECK (employment_status IN ('ACTIVE', 'INACTIVE'))
);

CREATE INDEX idx_employees_department_id ON employees (department_id);
CREATE INDEX idx_employees_employment_status ON employees (employment_status);
