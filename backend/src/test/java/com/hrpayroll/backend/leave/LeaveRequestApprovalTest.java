package com.hrpayroll.backend.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hrpayroll.backend.security.jwt.JwtService;
import com.hrpayroll.backend.support.LeaveTestFixtures;
import com.hrpayroll.backend.user.AccountStatus;
import com.hrpayroll.backend.user.User;
import com.hrpayroll.backend.user.UserRole;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class LeaveRequestApprovalTest {

    private static final LocalDate MON = LocalDate.of(2026, 9, 7);
    private static final LocalDate FRI = LocalDate.of(2026, 9, 11);
    private static final BigDecimal FIVE = new BigDecimal("5.0");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    private long employeeId;
    private long annualLeaveTypeId;
    private long adminUserId;
    private String adminToken;
    private String employeeToken;

    @BeforeEach
    void setUp() {
        LeaveTestFixtures.cleanup(jdbcTemplate);
        long departmentId = LeaveTestFixtures.installDepartment(jdbcTemplate);
        adminUserId = LeaveTestFixtures.installAdminUser(jdbcTemplate, passwordEncoder);

        long[] employeeAndUser = LeaveTestFixtures.installEmployeeWithUser(
                jdbcTemplate, passwordEncoder, departmentId, "APR", "770101-04-0001");
        employeeId = employeeAndUser[0];
        long employeeUserId = employeeAndUser[1];

        annualLeaveTypeId = LeaveTestFixtures.findLeaveTypeIdByName(jdbcTemplate, LeaveTestFixtures.ANNUAL_LEAVE_TYPE_NAME);

        adminToken = jwtService.generateAccessToken(new User(
                adminUserId, LeaveTestFixtures.ADMIN_EMAIL, "hash", UserRole.HR_ADMIN, AccountStatus.ACTIVE, null));
        employeeToken = jwtService.generateAccessToken(new User(
                employeeUserId, LeaveTestFixtures.EMPLOYEE_EMAIL, "hash", UserRole.EMPLOYEE, AccountStatus.ACTIVE, employeeId));
    }

    @AfterEach
    void tearDown() {
        LeaveTestFixtures.cleanup(jdbcTemplate);
    }

    private long insertPendingRequest() {
        return LeaveTestFixtures.insertLeaveRequest(jdbcTemplate, employeeId, annualLeaveTypeId, MON, FRI, FIVE, "PENDING");
    }

    private long insertPendingHalfDayRequest() {
        return LeaveTestFixtures.insertHalfDayLeaveRequest(jdbcTemplate, employeeId, annualLeaveTypeId, MON, "PENDING");
    }

    private String dbStatus(long requestId) {
        return jdbcTemplate.queryForObject("SELECT status FROM leave_requests WHERE id = ?", String.class, requestId);
    }

    private BigDecimal dbUsedDays() {
        return jdbcTemplate.queryForObject(
                "SELECT used_days FROM leave_balances WHERE employee_id = ? AND leave_type_id = ? AND year = 2026",
                BigDecimal.class,
                employeeId,
                annualLeaveTypeId);
    }

    // ---- Successful approval ----

    @Test
    void successfulApprovalChangesStatusAndReviewFields() throws Exception {
        long requestId = insertPendingRequest();
        LeaveTestFixtures.insertLeaveBalance(jdbcTemplate, employeeId, annualLeaveTypeId, 2026, new BigDecimal("14.0"), BigDecimal.ZERO);

        mockMvc.perform(put("/api/leave-requests/{id}/approve", requestId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.reviewedByUserId").value(adminUserId))
                .andExpect(jsonPath("$.reviewedAt").exists())
                .andExpect(jsonPath("$.rejectionReason").doesNotExist());
    }

    @Test
    void approvalDeductsUsedDaysByExactlyDaysRequested() throws Exception {
        long requestId = insertPendingRequest();
        LeaveTestFixtures.insertLeaveBalance(jdbcTemplate, employeeId, annualLeaveTypeId, 2026, new BigDecimal("14.0"), new BigDecimal("2.0"));

        mockMvc.perform(put("/api/leave-requests/{id}/approve", requestId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        assertThat(dbUsedDays()).isEqualByComparingTo("7.0");
    }

    @Test
    void approvalLeavesEntitledDaysUnchanged() throws Exception {
        long requestId = insertPendingRequest();
        LeaveTestFixtures.insertLeaveBalance(jdbcTemplate, employeeId, annualLeaveTypeId, 2026, new BigDecimal("14.0"), BigDecimal.ZERO);

        mockMvc.perform(put("/api/leave-requests/{id}/approve", requestId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        BigDecimal entitledDays = jdbcTemplate.queryForObject(
                "SELECT entitled_days FROM leave_balances WHERE employee_id = ? AND leave_type_id = ? AND year = 2026",
                BigDecimal.class,
                employeeId,
                annualLeaveTypeId);
        assertThat(entitledDays).isEqualByComparingTo("14.0");
    }

    @Test
    void approvalDoesNotModifyUnrelatedBalances() throws Exception {
        long requestId = insertPendingRequest();
        LeaveTestFixtures.insertLeaveBalance(jdbcTemplate, employeeId, annualLeaveTypeId, 2026, new BigDecimal("14.0"), BigDecimal.ZERO);
        long sickLeaveTypeId = LeaveTestFixtures.findLeaveTypeIdByName(jdbcTemplate, LeaveTestFixtures.SICK_LEAVE_TYPE_NAME);
        LeaveTestFixtures.insertLeaveBalance(jdbcTemplate, employeeId, sickLeaveTypeId, 2026, new BigDecimal("14.0"), new BigDecimal("3.0"));

        mockMvc.perform(put("/api/leave-requests/{id}/approve", requestId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        BigDecimal sickUsedDays = jdbcTemplate.queryForObject(
                "SELECT used_days FROM leave_balances WHERE employee_id = ? AND leave_type_id = ? AND year = 2026",
                BigDecimal.class,
                employeeId,
                sickLeaveTypeId);
        assertThat(sickUsedDays).isEqualByComparingTo("3.0");
    }

    // ---- Missing / insufficient balance ----

    @Test
    void missingMatchingBalanceReturnsLeaveBalanceNotFound() throws Exception {
        long requestId = insertPendingRequest();

        mockMvc.perform(put("/api/leave-requests/{id}/approve", requestId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LEAVE_BALANCE_NOT_FOUND"));
    }

    @Test
    void missingBalanceFailureRollsRequestBackToPending() throws Exception {
        long requestId = insertPendingRequest();

        mockMvc.perform(put("/api/leave-requests/{id}/approve", requestId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound());

        assertThat(dbStatus(requestId)).isEqualTo("PENDING");
        Long reviewedBy = jdbcTemplate.queryForObject("SELECT reviewed_by FROM leave_requests WHERE id = ?", Long.class, requestId);
        assertThat(reviewedBy).isNull();
    }

    @Test
    void insufficientBalanceReturnsInsufficientLeaveBalance() throws Exception {
        long requestId = insertPendingRequest();
        LeaveTestFixtures.insertLeaveBalance(jdbcTemplate, employeeId, annualLeaveTypeId, 2026, new BigDecimal("2.0"), BigDecimal.ZERO);

        mockMvc.perform(put("/api/leave-requests/{id}/approve", requestId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_LEAVE_BALANCE"));
    }

    @Test
    void insufficientBalanceFailureRollsBackAndLeavesUsedDaysUnchanged() throws Exception {
        long requestId = insertPendingRequest();
        LeaveTestFixtures.insertLeaveBalance(jdbcTemplate, employeeId, annualLeaveTypeId, 2026, new BigDecimal("2.0"), BigDecimal.ZERO);

        mockMvc.perform(put("/api/leave-requests/{id}/approve", requestId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isConflict());

        assertThat(dbStatus(requestId)).isEqualTo("PENDING");
        assertThat(dbUsedDays()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ---- Re-processing ----

    @Test
    void reApprovingAnAlreadyApprovedRequestReturnsAlreadyProcessed() throws Exception {
        long requestId = insertPendingRequest();
        LeaveTestFixtures.insertLeaveBalance(jdbcTemplate, employeeId, annualLeaveTypeId, 2026, new BigDecimal("14.0"), BigDecimal.ZERO);

        mockMvc.perform(put("/api/leave-requests/{id}/approve", requestId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/leave-requests/{id}/approve", requestId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEAVE_REQUEST_ALREADY_PROCESSED"));
    }

    @Test
    void approvingAnAlreadyRejectedRequestReturnsAlreadyProcessed() throws Exception {
        long requestId = insertPendingRequest();

        mockMvc.perform(put("/api/leave-requests/{id}/reject", requestId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/leave-requests/{id}/approve", requestId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEAVE_REQUEST_ALREADY_PROCESSED"));
    }

    @Test
    void reRejectingAnAlreadyRejectedRequestReturnsAlreadyProcessed() throws Exception {
        long requestId = insertPendingRequest();

        mockMvc.perform(put("/api/leave-requests/{id}/reject", requestId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/leave-requests/{id}/reject", requestId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEAVE_REQUEST_ALREADY_PROCESSED"));
    }

    @Test
    void rejectingAnAlreadyApprovedRequestReturnsAlreadyProcessed() throws Exception {
        long requestId = insertPendingRequest();
        LeaveTestFixtures.insertLeaveBalance(jdbcTemplate, employeeId, annualLeaveTypeId, 2026, new BigDecimal("14.0"), BigDecimal.ZERO);

        mockMvc.perform(put("/api/leave-requests/{id}/approve", requestId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/leave-requests/{id}/reject", requestId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEAVE_REQUEST_ALREADY_PROCESSED"));
    }

    // ---- Rejection ----

    @Test
    void successfulRejectionSetsReviewFieldsAndOptionalReason() throws Exception {
        long requestId = insertPendingRequest();

        mockMvc.perform(put("/api/leave-requests/{id}/reject", requestId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Short staffed that week\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reviewedByUserId").value(adminUserId))
                .andExpect(jsonPath("$.reviewedAt").exists())
                .andExpect(jsonPath("$.rejectionReason").value("Short staffed that week"));
    }

    @Test
    void omittedBodyRejectionIsAllowedAndReasonIsNull() throws Exception {
        long requestId = insertPendingRequest();

        mockMvc.perform(put("/api/leave-requests/{id}/reject", requestId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").doesNotExist());
    }

    @Test
    void emptyJsonObjectRejectionIsAllowedAndReasonIsNull() throws Exception {
        long requestId = insertPendingRequest();

        mockMvc.perform(put("/api/leave-requests/{id}/reject", requestId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejectionReason").doesNotExist());
    }

    @Test
    void whitespaceOnlyReasonIsNormalizedToNull() throws Exception {
        long requestId = insertPendingRequest();

        mockMvc.perform(put("/api/leave-requests/{id}/reject", requestId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"   \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejectionReason").doesNotExist());

        String storedReason = jdbcTemplate.queryForObject(
                "SELECT rejection_reason FROM leave_requests WHERE id = ?", String.class, requestId);
        assertThat(storedReason).isNull();
    }

    @Test
    void rejectionLeavesEveryBalanceUnchanged() throws Exception {
        long requestId = insertPendingRequest();
        LeaveTestFixtures.insertLeaveBalance(jdbcTemplate, employeeId, annualLeaveTypeId, 2026, new BigDecimal("14.0"), new BigDecimal("1.0"));

        mockMvc.perform(put("/api/leave-requests/{id}/reject", requestId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        assertThat(dbUsedDays()).isEqualByComparingTo("1.0");
    }

    // ---- Half-day approval/rejection ----

    @Test
    void approvingHalfDayIncrementsUsedDaysByExactlyZeroPointFive() throws Exception {
        long requestId = insertPendingHalfDayRequest();
        LeaveTestFixtures.insertLeaveBalance(jdbcTemplate, employeeId, annualLeaveTypeId, 2026, new BigDecimal("14.0"), BigDecimal.ZERO);

        mockMvc.perform(put("/api/leave-requests/{id}/approve", requestId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.durationType").value("HALF_DAY"));

        assertThat(dbUsedDays()).isEqualByComparingTo("0.5");
    }

    @Test
    void rejectingHalfDayLeavesUsedDaysUnchanged() throws Exception {
        long requestId = insertPendingHalfDayRequest();
        LeaveTestFixtures.insertLeaveBalance(jdbcTemplate, employeeId, annualLeaveTypeId, 2026, new BigDecimal("14.0"), BigDecimal.ZERO);

        mockMvc.perform(put("/api/leave-requests/{id}/reject", requestId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        assertThat(dbUsedDays()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void missingBalanceForHalfDayReturnsLeaveBalanceNotFoundAndRollsBackToPending() throws Exception {
        long requestId = insertPendingHalfDayRequest();

        mockMvc.perform(put("/api/leave-requests/{id}/approve", requestId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LEAVE_BALANCE_NOT_FOUND"));

        assertThat(dbStatus(requestId)).isEqualTo("PENDING");
    }

    @Test
    void balanceWithLessThanHalfDayRemainingReturnsInsufficientBalanceAndDoesNotMutate() throws Exception {
        long requestId = insertPendingHalfDayRequest();
        // remaining = 1.0 - 0.6 = 0.4, less than the 0.5 requested
        LeaveTestFixtures.insertLeaveBalance(jdbcTemplate, employeeId, annualLeaveTypeId, 2026, new BigDecimal("1.0"), new BigDecimal("0.6"));

        mockMvc.perform(put("/api/leave-requests/{id}/approve", requestId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_LEAVE_BALANCE"));

        assertThat(dbStatus(requestId)).isEqualTo("PENDING");
        assertThat(dbUsedDays()).isEqualByComparingTo("0.6");
    }

    @Test
    void reApprovingAnAlreadyProcessedHalfDayRequestReturnsAlreadyProcessed() throws Exception {
        long requestId = insertPendingHalfDayRequest();
        LeaveTestFixtures.insertLeaveBalance(jdbcTemplate, employeeId, annualLeaveTypeId, 2026, new BigDecimal("14.0"), BigDecimal.ZERO);

        mockMvc.perform(put("/api/leave-requests/{id}/approve", requestId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/leave-requests/{id}/approve", requestId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEAVE_REQUEST_ALREADY_PROCESSED"));
    }

    @Test
    void approvalAuditForHalfDayIncludesDurationType() throws Exception {
        long requestId = insertPendingHalfDayRequest();
        LeaveTestFixtures.insertLeaveBalance(jdbcTemplate, employeeId, annualLeaveTypeId, 2026, new BigDecimal("14.0"), BigDecimal.ZERO);

        mockMvc.perform(put("/api/leave-requests/{id}/approve", requestId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        String details = jdbcTemplate.queryForObject(
                "SELECT details::text FROM audit_logs WHERE action = 'LEAVE_REQUEST_APPROVED' AND entity_id = ?",
                String.class,
                requestId);
        assertThat(details).contains("durationType").contains("HALF_DAY");
    }

    @Test
    void rejectionAuditForHalfDayIncludesDurationTypeAndNoRawReasonText() throws Exception {
        long requestId = insertPendingHalfDayRequest();
        String secretReason = "UNMISTAKABLE_HALF_DAY_SECRET_998877";

        mockMvc.perform(put("/api/leave-requests/{id}/reject", requestId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + secretReason + "\"}"))
                .andExpect(status().isOk());

        String details = jdbcTemplate.queryForObject(
                "SELECT details::text FROM audit_logs WHERE action = 'LEAVE_REQUEST_REJECTED' AND entity_id = ?",
                String.class,
                requestId);
        assertThat(details).contains("durationType").contains("HALF_DAY");
        assertThat(details).doesNotContain(secretReason);
    }

    // ---- 404 / authorization ----

    @Test
    void approveUnknownRequestReturns404() throws Exception {
        mockMvc.perform(put("/api/leave-requests/{id}/approve", 999_999_999L).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectUnknownRequestReturns404() throws Exception {
        mockMvc.perform(put("/api/leave-requests/{id}/reject", 999_999_999L).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void employeeReceives403OnApproveAndReject() throws Exception {
        long requestId = insertPendingRequest();

        mockMvc.perform(put("/api/leave-requests/{id}/approve", requestId).header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/leave-requests/{id}/reject", requestId).header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedReceives401OnApproveAndReject() throws Exception {
        long requestId = insertPendingRequest();

        mockMvc.perform(put("/api/leave-requests/{id}/approve", requestId)).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/leave-requests/{id}/reject", requestId)).andExpect(status().isUnauthorized());
    }

    // ---- Audit ----

    @Test
    void successfulApprovalCreatesAuditRow() throws Exception {
        long requestId = insertPendingRequest();
        LeaveTestFixtures.insertLeaveBalance(jdbcTemplate, employeeId, annualLeaveTypeId, 2026, new BigDecimal("14.0"), BigDecimal.ZERO);

        mockMvc.perform(put("/api/leave-requests/{id}/approve", requestId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        String details = jdbcTemplate.queryForObject(
                "SELECT details::text FROM audit_logs WHERE action = 'LEAVE_REQUEST_APPROVED' AND entity_id = ?",
                String.class,
                requestId);
        assertThat(details).contains("leaveRequestId").contains("employeeId").contains("daysRequested");
    }

    @Test
    void successfulRejectionCreatesAuditRowWithHasReasonFlagAndNoRawText() throws Exception {
        long requestId = insertPendingRequest();

        mockMvc.perform(put("/api/leave-requests/{id}/reject", requestId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Very private staffing reason\"}"))
                .andExpect(status().isOk());

        String details = jdbcTemplate.queryForObject(
                "SELECT details::text FROM audit_logs WHERE action = 'LEAVE_REQUEST_REJECTED' AND entity_id = ?",
                String.class,
                requestId);
        assertThat(details).contains("leaveRequestId").contains("employeeId").contains("\"hasReason\": true");
        assertThat(details).doesNotContain("Very private staffing reason");
    }

    @Test
    void rejectionWithNoReasonRecordsHasReasonFalse() throws Exception {
        long requestId = insertPendingRequest();

        mockMvc.perform(put("/api/leave-requests/{id}/reject", requestId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        String details = jdbcTemplate.queryForObject(
                "SELECT details::text FROM audit_logs WHERE action = 'LEAVE_REQUEST_REJECTED' AND entity_id = ?",
                String.class,
                requestId);
        assertThat(details).contains("\"hasReason\": false");
    }

    @Test
    void failedApprovalDoesNotCreateAnApprovalAuditRow() throws Exception {
        long requestId = insertPendingRequest();
        LeaveTestFixtures.insertLeaveBalance(jdbcTemplate, employeeId, annualLeaveTypeId, 2026, new BigDecimal("2.0"), BigDecimal.ZERO);

        mockMvc.perform(put("/api/leave-requests/{id}/approve", requestId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isConflict());

        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = 'LEAVE_REQUEST_APPROVED' AND entity_id = ?",
                Integer.class,
                requestId);
        assertThat(auditCount).isZero();
    }

    @Test
    void rawRejectionReasonTextNeverAppearsAnywhereInAuditLogs() throws Exception {
        long requestId = insertPendingRequest();
        String secretReason = "UNMISTAKABLE_SECRET_REASON_TOKEN_998877";

        mockMvc.perform(put("/api/leave-requests/{id}/reject", requestId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + secretReason + "\"}"))
                .andExpect(status().isOk());

        Integer matchingRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE details::text LIKE ?", Integer.class, "%" + secretReason + "%");
        assertThat(matchingRows).isZero();
    }
}
