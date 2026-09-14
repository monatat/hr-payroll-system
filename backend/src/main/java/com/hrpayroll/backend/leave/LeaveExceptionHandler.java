package com.hrpayroll.backend.leave;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** One handler for the leave domain's business-rule exceptions, mirroring AuthExceptionHandler/SalaryExceptionHandler's flat ProblemDetail-building style. */
@RestControllerAdvice
public class LeaveExceptionHandler {

    private static final String ALREADY_PROCESSED_DETAIL =
            "This leave request has already been processed and can no longer be approved or rejected.";

    @ExceptionHandler(InvalidLeaveDatesException.class)
    public ResponseEntity<ProblemDetail> handleInvalidDates(InvalidLeaveDatesException ex) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_LEAVE_DATES", ex.getMessage());
    }

    @ExceptionHandler(ZeroWorkingDaysRequestedException.class)
    public ResponseEntity<ProblemDetail> handleZeroWorkingDays(ZeroWorkingDaysRequestedException ex) {
        return problem(HttpStatus.BAD_REQUEST, "ZERO_WORKING_DAYS_REQUESTED", ex.getMessage());
    }

    @ExceptionHandler(LeaveRequestOverlapException.class)
    public ResponseEntity<ProblemDetail> handleOverlap(LeaveRequestOverlapException ex) {
        return problem(HttpStatus.CONFLICT, "LEAVE_REQUEST_OVERLAP", ex.getMessage());
    }

    @ExceptionHandler(LeaveRequestAlreadyProcessedException.class)
    public ResponseEntity<ProblemDetail> handleAlreadyProcessed(LeaveRequestAlreadyProcessedException ex) {
        return problem(HttpStatus.CONFLICT, "LEAVE_REQUEST_ALREADY_PROCESSED", ex.getMessage());
    }

    @ExceptionHandler(LeaveBalanceNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleBalanceNotFound(LeaveBalanceNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "LEAVE_BALANCE_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(InsufficientLeaveBalanceException.class)
    public ResponseEntity<ProblemDetail> handleInsufficientBalance(InsufficientLeaveBalanceException ex) {
        return problem(HttpStatus.CONFLICT, "INSUFFICIENT_LEAVE_BALANCE", ex.getMessage());
    }

    /**
     * Safety net: an optimistic-lock failure can surface at transaction
     * commit time, after LeaveRequestService's own local catch (around its
     * deliberate early flush) has already run and returned -- so this must
     * still be handled here too. Deliberately uses a fixed detail message,
     * never {@code ex.getMessage()}, which for
     * ObjectOptimisticLockingFailureException includes the entity's class
     * name and identifier -- internal details that must never reach a
     * client.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLockConflict(ObjectOptimisticLockingFailureException ex) {
        return problem(HttpStatus.CONFLICT, "LEAVE_REQUEST_ALREADY_PROCESSED", ALREADY_PROCESSED_DETAIL);
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        return ResponseEntity.status(status).body(problem);
    }
}
