package com.hrpayroll.backend.attendance;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * One handler for the attendance domain's business-rule exceptions, mirroring
 * LeaveExceptionHandler/SalaryExceptionHandler's flat ProblemDetail-building style. Duplicate
 * clock-in is handled separately by {@code ApiExceptionHandler}'s constraint-name map, since it is
 * a database-uniqueness conflict rather than an application-level domain exception.
 */
@RestControllerAdvice
public class AttendanceExceptionHandler {

    @ExceptionHandler(AttendanceNoOpenRecordException.class)
    public ResponseEntity<ProblemDetail> handleNoOpenRecord(AttendanceNoOpenRecordException ex) {
        return problem(HttpStatus.NOT_FOUND, "ATTENDANCE_NO_OPEN_RECORD", ex.getMessage());
    }

    @ExceptionHandler(AttendanceAlreadyClockedOutException.class)
    public ResponseEntity<ProblemDetail> handleAlreadyClockedOut(AttendanceAlreadyClockedOutException ex) {
        return problem(HttpStatus.CONFLICT, "ATTENDANCE_ALREADY_CLOCKED_OUT", ex.getMessage());
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        return ResponseEntity.status(status).body(problem);
    }
}
