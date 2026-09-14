package com.hrpayroll.backend.common.error;

import java.util.Map;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates database uniqueness violations into clean 409 responses.
 * Database constraints (uq_departments_name, uq_employees_employee_code,
 * uq_employees_ic_number) remain the sole authoritative source of truth for
 * uniqueness -- this handler only maps the resulting exception to an HTTP
 * response, it never performs a racy "check-then-insert".
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Map<String, String> CONSTRAINT_CODES = Map.of(
            "uq_departments_name", "DUPLICATE_DEPARTMENT_NAME",
            "uq_employees_employee_code", "DUPLICATE_EMPLOYEE_CODE",
            "uq_employees_ic_number", "DUPLICATE_IC_NUMBER");

    private static final Map<String, String> CONSTRAINT_DETAILS = Map.of(
            "uq_departments_name", "A department with this name already exists.",
            "uq_employees_employee_code", "An employee with this employee code already exists.",
            "uq_employees_ic_number", "An employee with this IC number already exists.");

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        String constraintName = extractConstraintName(ex);
        String code = CONSTRAINT_CODES.getOrDefault(constraintName, "DUPLICATE_RESOURCE");
        String detail = CONSTRAINT_DETAILS.getOrDefault(constraintName, "This resource conflicts with an existing record.");

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detail);
        problem.setTitle("Conflict");
        problem.setProperty("code", code);

        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    private static String extractConstraintName(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof ConstraintViolationException cve) {
                return cve.getConstraintName();
            }
            current = current.getCause();
        }
        return null;
    }
}
