CREATE TABLE users (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email VARCHAR(150) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL,
    account_status VARCHAR(20) NOT NULL,
    employee_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT uq_users_employee_id UNIQUE (employee_id),
    CONSTRAINT fk_users_employee FOREIGN KEY (employee_id)
        REFERENCES employees (id) ON DELETE RESTRICT,
    CONSTRAINT chk_users_role CHECK (role IN ('HR_ADMIN', 'EMPLOYEE')),
    CONSTRAINT chk_users_account_status CHECK (account_status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT chk_users_role_employee_id CHECK (
        (role = 'EMPLOYEE' AND employee_id IS NOT NULL) OR
        (role = 'HR_ADMIN' AND employee_id IS NULL)
    )
);
