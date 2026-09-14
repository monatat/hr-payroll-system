package com.hrpayroll.backend.payroll;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * One handler for the payroll domain's business-rule exceptions, mirroring
 * LeaveExceptionHandler/SalaryExceptionHandler's flat ProblemDetail-building style. This is the
 * single authoritative path for {@code uq_payroll_runs_period} violations: they never reach here
 * as a raw {@code DataIntegrityViolationException} in the first place, because
 * {@code PayrollRunTransactionService.createRun} already catches and translates the constraint
 * violation into {@link PayrollRunPeriodAlreadyExistsException} before it ever leaves the service
 * layer. Adding {@code uq_payroll_runs_period} to {@code ApiExceptionHandler}'s generic
 * constraint-name map as well was deliberately skipped: with the service already catching it
 * first, that second entry would be unreachable dead code for this flow, and maintaining two
 * separate response shapes for the same conceptual error (one from each handler) for a path that
 * cannot actually occur is not a genuine defence-in-depth benefit.
 */
@RestControllerAdvice
public class PayrollExceptionHandler {

    @ExceptionHandler(InvalidPayrollPeriodException.class)
    public ResponseEntity<ProblemDetail> handleInvalidPeriod(InvalidPayrollPeriodException ex) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_PAYROLL_PERIOD", ex.getMessage());
    }

    @ExceptionHandler(PayrollRunPeriodAlreadyExistsException.class)
    public ResponseEntity<ProblemDetail> handleDuplicatePeriod(PayrollRunPeriodAlreadyExistsException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle(HttpStatus.CONFLICT.getReasonPhrase());
        problem.setProperty("code", "PAYROLL_RUN_PERIOD_ALREADY_EXISTS");
        problem.setProperty("periodMonth", ex.getPeriodMonth());
        problem.setProperty("periodYear", ex.getPeriodYear());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * Detail is exactly the exception's own sanitized safe message (never a stack trace, SQL, raw
     * database exception message, or a salary/deduction/rate value -- PayrollRunTransactionService
     * already guarantees that at the source).
     */
    @ExceptionHandler(PayrollRunFailedException.class)
    public ResponseEntity<ProblemDetail> handleCalculationFailure(PayrollRunFailedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getSafeMessage());
        problem.setTitle(HttpStatus.UNPROCESSABLE_ENTITY.getReasonPhrase());
        problem.setProperty("code", "PAYROLL_RUN_FAILED");
        problem.setProperty("payrollRunId", ex.getPayrollRunId());
        problem.setProperty("failureCode", ex.getFailureCode().name());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem);
    }

    @ExceptionHandler(PayrollRunNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleRunNotFound(PayrollRunNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "PAYROLL_RUN_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(PayslipNotFoundException.class)
    public ResponseEntity<ProblemDetail> handlePayslipNotFound(PayslipNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "PAYSLIP_NOT_FOUND", ex.getMessage());
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        return ResponseEntity.status(status).body(problem);
    }
}
