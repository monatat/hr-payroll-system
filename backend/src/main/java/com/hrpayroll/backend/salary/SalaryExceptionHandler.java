package com.hrpayroll.backend.salary;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class SalaryExceptionHandler {

    @ExceptionHandler(SalaryStructureOverlapException.class)
    public ResponseEntity<ProblemDetail> handleOverlap(SalaryStructureOverlapException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Conflict");
        problem.setProperty("code", "SALARY_STRUCTURE_OVERLAP");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }
}
