package com.hrpayroll.backend.leave;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Simulates an optimistic-lock conflict directly (no Spring context, no
 * concurrency) to prove the safety-net handler maps it to a stable,
 * detail-free 409 -- complementing LeaveRequestConcurrencyTest, which
 * proves the same mapping is reached via a genuine concurrent race.
 */
class LeaveExceptionHandlerTest {

    @Test
    void optimisticLockConflictMapsToStable409WithoutExposingInternalDetails() {
        // ObjectOptimisticLockingFailureException's own message includes the
        // entity class name and identifier -- exactly what must never reach
        // a client.
        ObjectOptimisticLockingFailureException ex =
                new ObjectOptimisticLockingFailureException(LeaveRequest.class, 14L);
        assertThat(ex.getMessage()).contains("LeaveRequest").contains("14");

        ResponseEntity<ProblemDetail> response = new LeaveExceptionHandler().handleOptimisticLockConflict(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ProblemDetail body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getProperties()).containsEntry("code", "LEAVE_REQUEST_ALREADY_PROCESSED");
        assertThat(body.getDetail()).doesNotContain("LeaveRequest").doesNotContain("14");
        assertThat(body.getDetail()).doesNotContainIgnoringCase("hibernate").doesNotContainIgnoringCase("version");
    }
}
