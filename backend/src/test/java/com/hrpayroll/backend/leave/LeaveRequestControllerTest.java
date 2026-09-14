package com.hrpayroll.backend.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrpayroll.backend.security.jwt.JwtService;
import com.hrpayroll.backend.support.LeaveTestFixtures;
import com.hrpayroll.backend.user.AccountStatus;
import com.hrpayroll.backend.user.User;
import com.hrpayroll.backend.user.UserRole;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
class LeaveRequestControllerTest {

    // Fixed, deterministic dates -- no reliance on "today". 2026-09-07 is a
    // Monday, 2026-09-11 the following Friday; 2026-09-14/09-18 the next
    // Mon-Fri (immediately adjacent, no gap); 2026-09-12/09-13 is a
    // Sat/Sun weekend.
    private static final LocalDate MON = LocalDate.of(2026, 9, 7);
    private static final LocalDate FRI = LocalDate.of(2026, 9, 11);
    private static final LocalDate NEXT_MON = LocalDate.of(2026, 9, 14);
    private static final LocalDate NEXT_FRI = LocalDate.of(2026, 9, 18);
    private static final LocalDate SAT = LocalDate.of(2026, 9, 12);
    private static final LocalDate SUN = LocalDate.of(2026, 9, 13);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ObjectMapper objectMapper;

    private long departmentId;
    private long employeeId;
    private long otherEmployeeId;
    private long annualLeaveTypeId;
    private long employeeUserId;
    private String adminToken;
    private String employeeToken;
    private String otherEmployeeToken;

    @BeforeEach
    void setUp() {
        LeaveTestFixtures.cleanup(jdbcTemplate);
        departmentId = LeaveTestFixtures.installDepartment(jdbcTemplate);
        long adminUserId = LeaveTestFixtures.installAdminUser(jdbcTemplate, passwordEncoder);

        long[] employeeAndUser =
                LeaveTestFixtures.installEmployeeWithUser(jdbcTemplate, passwordEncoder, departmentId, "REQ", "770101-03-0001");
        employeeId = employeeAndUser[0];
        employeeUserId = employeeAndUser[1];

        long[] otherEmployeeAndUser = LeaveTestFixtures.installOtherEmployeeWithUser(
                jdbcTemplate, passwordEncoder, departmentId, "REQOTHER", "770101-03-0002");
        otherEmployeeId = otherEmployeeAndUser[0];
        long otherEmployeeUserId = otherEmployeeAndUser[1];

        annualLeaveTypeId = LeaveTestFixtures.findLeaveTypeIdByName(jdbcTemplate, LeaveTestFixtures.ANNUAL_LEAVE_TYPE_NAME);

        adminToken = jwtService.generateAccessToken(new User(
                adminUserId, LeaveTestFixtures.ADMIN_EMAIL, "hash", UserRole.HR_ADMIN, AccountStatus.ACTIVE, null));
        employeeToken = jwtService.generateAccessToken(new User(
                employeeUserId, LeaveTestFixtures.EMPLOYEE_EMAIL, "hash", UserRole.EMPLOYEE, AccountStatus.ACTIVE, employeeId));
        otherEmployeeToken = jwtService.generateAccessToken(new User(
                otherEmployeeUserId, LeaveTestFixtures.OTHER_EMPLOYEE_EMAIL, "hash", UserRole.EMPLOYEE, AccountStatus.ACTIVE,
                otherEmployeeId));
    }

    @AfterEach
    void tearDown() {
        LeaveTestFixtures.cleanup(jdbcTemplate);
    }

    private String createBody(Long leaveTypeId, LocalDate from, LocalDate to, String reason) {
        return createBody(leaveTypeId, from, to, reason, null);
    }

    private String createBody(Long leaveTypeId, LocalDate from, LocalDate to, String reason, String durationType) {
        StringBuilder json = new StringBuilder("{");
        json.append("\"leaveTypeId\":").append(leaveTypeId).append(",");
        json.append("\"startDate\":\"").append(from).append("\",");
        json.append("\"endDate\":\"").append(to).append("\"");
        if (reason != null) {
            json.append(",\"reason\":\"").append(reason).append("\"");
        }
        if (durationType != null) {
            json.append(",\"durationType\":\"").append(durationType).append("\"");
        }
        json.append("}");
        return json.toString();
    }

    // ---- Authorization ----

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        mockMvc.perform(post("/api/leave-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(annualLeaveTypeId, MON, FRI, null)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/leave-requests")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/leave-requests/{id}", 1)).andExpect(status().isUnauthorized());
    }

    @Test
    void hrAdminCannotCreateALeaveRequest() throws Exception {
        mockMvc.perform(post("/api/leave-requests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(annualLeaveTypeId, MON, FRI, null)))
                .andExpect(status().isForbidden());
    }

    @Test
    void employeeCannotUseTheAdminListEndpoint() throws Exception {
        mockMvc.perform(get("/api/leave-requests").header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken))
                .andExpect(status().isForbidden());
    }

    // ---- Creation ----

    @Test
    void successfulCreationReturns201WithServerComputedDaysRequested() throws Exception {
        mockMvc.perform(post("/api/leave-requests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(annualLeaveTypeId, MON, FRI, "Family trip")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.employeeId").value(employeeId))
                .andExpect(jsonPath("$.leaveTypeId").value(annualLeaveTypeId))
                .andExpect(jsonPath("$.leaveTypeName").value("Annual Leave"))
                .andExpect(jsonPath("$.daysRequested").value(5.0))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.reason").value("Family trip"))
                .andExpect(jsonPath("$.reviewedByUserId").doesNotExist())
                .andExpect(jsonPath("$.reviewedAt").doesNotExist())
                .andExpect(jsonPath("$.rejectionReason").doesNotExist());
    }

    @Test
    void employeeIdCannotBeSuppliedByTheClient() throws Exception {
        String rawBody = "{"
                + "\"employeeId\":" + otherEmployeeId + ","
                + "\"leaveTypeId\":" + annualLeaveTypeId + ","
                + "\"startDate\":\"" + MON + "\","
                + "\"endDate\":\"" + FRI + "\""
                + "}";

        mockMvc.perform(post("/api/leave-requests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rawBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.employeeId").value(employeeId));

        Long storedEmployeeId = jdbcTemplate.queryForObject(
                "SELECT employee_id FROM leave_requests WHERE employee_id = ?", Long.class, employeeId);
        assertThat(storedEmployeeId).isEqualTo(employeeId);
    }

    @Test
    void reversedDatesReturnInvalidLeaveDates() throws Exception {
        mockMvc.perform(post("/api/leave-requests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(annualLeaveTypeId, FRI, MON, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LEAVE_DATES"));
    }

    @Test
    void crossYearDatesReturnInvalidLeaveDates() throws Exception {
        mockMvc.perform(post("/api/leave-requests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(annualLeaveTypeId, LocalDate.of(2026, 12, 30), LocalDate.of(2027, 1, 2), null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LEAVE_DATES"));
    }

    @Test
    void weekendOnlyDatesReturnZeroWorkingDaysRequested() throws Exception {
        mockMvc.perform(post("/api/leave-requests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(annualLeaveTypeId, SAT, SUN, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ZERO_WORKING_DAYS_REQUESTED"));
    }

    @Test
    void unknownLeaveTypeReturns404() throws Exception {
        mockMvc.perform(post("/api/leave-requests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(annualLeaveTypeId + 999_999, MON, FRI, null)))
                .andExpect(status().isNotFound());
    }

    @Test
    void overlappingPendingRequestReturns409() throws Exception {
        mockMvc.perform(post("/api/leave-requests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(annualLeaveTypeId, MON, FRI, null)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/leave-requests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(annualLeaveTypeId, LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 15), null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEAVE_REQUEST_OVERLAP"));
    }

    @Test
    void overlappingApprovedRequestReturns409() throws Exception {
        LeaveTestFixtures.insertLeaveRequest(
                jdbcTemplate, employeeId, annualLeaveTypeId, MON, FRI, new BigDecimal("5.0"), "APPROVED");

        mockMvc.perform(post("/api/leave-requests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(annualLeaveTypeId, LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 15), null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEAVE_REQUEST_OVERLAP"));
    }

    @Test
    void rejectedRequestDoesNotBlockANewOverlappingRequest() throws Exception {
        LeaveTestFixtures.insertLeaveRequest(
                jdbcTemplate, employeeId, annualLeaveTypeId, MON, FRI, new BigDecimal("5.0"), "REJECTED");

        mockMvc.perform(post("/api/leave-requests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(annualLeaveTypeId, MON, FRI, null)))
                .andExpect(status().isCreated());
    }

    @Test
    void adjacentNonOverlappingDatesAreAllowed() throws Exception {
        mockMvc.perform(post("/api/leave-requests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(annualLeaveTypeId, MON, FRI, null)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/leave-requests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(annualLeaveTypeId, NEXT_MON, NEXT_FRI, null)))
                .andExpect(status().isCreated());
    }

    @Test
    void createdRequestHasPendingStatusAndNullReviewFieldsInTheDatabase() throws Exception {
        mockMvc.perform(post("/api/leave-requests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(annualLeaveTypeId, MON, FRI, null)))
                .andExpect(status().isCreated());

        Long reviewedBy = jdbcTemplate.queryForObject(
                "SELECT reviewed_by FROM leave_requests WHERE employee_id = ?", Long.class, employeeId);
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM leave_requests WHERE employee_id = ?", String.class, employeeId);
        assertThat(reviewedBy).isNull();
        assertThat(status).isEqualTo("PENDING");
    }

    @Test
    void auditRowExistsAndDoesNotContainTheSubmittedReasonText() throws Exception {
        mockMvc.perform(post("/api/leave-requests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(annualLeaveTypeId, MON, FRI, "Very private medical reason")))
                .andExpect(status().isCreated());

        String details = jdbcTemplate.queryForObject(
                "SELECT details::text FROM audit_logs WHERE action = 'LEAVE_REQUEST_CREATED' AND actor_id = ?",
                String.class,
                employeeUserId);
        assertThat(details).contains("employeeId").contains("leaveTypeId").contains("daysRequested");
        assertThat(details).doesNotContain("Very private medical reason");
    }

    // ---- Half-day creation ----

    @Test
    void halfDayOnAWeekdayReturns201WithHalfDayAmount() throws Exception {
        mockMvc.perform(post("/api/leave-requests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(annualLeaveTypeId, MON, MON, null, "HALF_DAY")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.durationType").value("HALF_DAY"))
                .andExpect(jsonPath("$.daysRequested").value(0.5));

        BigDecimal storedDays = jdbcTemplate.queryForObject(
                "SELECT days_requested FROM leave_requests WHERE employee_id = ?", BigDecimal.class, employeeId);
        String storedDuration = jdbcTemplate.queryForObject(
                "SELECT duration_type FROM leave_requests WHERE employee_id = ?", String.class, employeeId);
        assertThat(storedDays).isEqualByComparingTo("0.5");
        assertThat(storedDuration).isEqualTo("HALF_DAY");
    }

    @Test
    void halfDayWithDifferentStartAndEndDatesReturnsInvalidLeaveDates() throws Exception {
        mockMvc.perform(post("/api/leave-requests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(annualLeaveTypeId, MON, FRI, null, "HALF_DAY")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LEAVE_DATES"));
    }

    @Test
    void halfDayOnAWeekendReturnsZeroWorkingDaysRequested() throws Exception {
        mockMvc.perform(post("/api/leave-requests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(annualLeaveTypeId, SAT, SAT, null, "HALF_DAY")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ZERO_WORKING_DAYS_REQUESTED"));
    }

    @Test
    void omittedDurationTypeDefaultsToFullDay() throws Exception {
        mockMvc.perform(post("/api/leave-requests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(annualLeaveTypeId, MON, FRI, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.durationType").value("FULL_DAY"));

        String storedDuration = jdbcTemplate.queryForObject(
                "SELECT duration_type FROM leave_requests WHERE employee_id = ?", String.class, employeeId);
        assertThat(storedDuration).isEqualTo("FULL_DAY");
    }

    @Test
    void explicitFullDayCalculationRemainsUnchanged() throws Exception {
        mockMvc.perform(post("/api/leave-requests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(annualLeaveTypeId, MON, FRI, null, "FULL_DAY")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.daysRequested").value(5.0))
                .andExpect(jsonPath("$.durationType").value("FULL_DAY"));
    }

    @Test
    void invalidDurationTypeJsonValueReturns400() throws Exception {
        String rawBody = "{\"leaveTypeId\":" + annualLeaveTypeId + ",\"startDate\":\"" + MON + "\",\"endDate\":\"" + MON
                + "\",\"durationType\":\"NOT_A_REAL_VALUE\"}";

        mockMvc.perform(post("/api/leave-requests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rawBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    void clientSuppliedDaysRequestedCannotOverrideTheServerComputedHalfDayValue() throws Exception {
        String rawBody = "{\"leaveTypeId\":" + annualLeaveTypeId + ",\"startDate\":\"" + MON + "\",\"endDate\":\"" + MON
                + "\",\"durationType\":\"HALF_DAY\",\"daysRequested\":99}";

        mockMvc.perform(post("/api/leave-requests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rawBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.daysRequested").value(0.5));
    }

    @Test
    void createdAuditIncludesDurationType() throws Exception {
        mockMvc.perform(post("/api/leave-requests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(annualLeaveTypeId, MON, MON, null, "HALF_DAY")))
                .andExpect(status().isCreated());

        String details = jdbcTemplate.queryForObject(
                "SELECT details::text FROM audit_logs WHERE action = 'LEAVE_REQUEST_CREATED' AND actor_id = ?",
                String.class,
                employeeUserId);
        assertThat(details).contains("durationType").contains("HALF_DAY");
    }

    // ---- Half-day overlap ----

    @Test
    void existingHalfDayBlocksANewFullDayCoveringThatDate() throws Exception {
        LeaveTestFixtures.insertHalfDayLeaveRequest(jdbcTemplate, employeeId, annualLeaveTypeId, MON, "PENDING");

        mockMvc.perform(post("/api/leave-requests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(annualLeaveTypeId, MON, FRI, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEAVE_REQUEST_OVERLAP"));
    }

    @Test
    void existingFullDayBlocksANewHalfDayOnThatDate() throws Exception {
        LeaveTestFixtures.insertLeaveRequest(
                jdbcTemplate, employeeId, annualLeaveTypeId, MON, FRI, new BigDecimal("5.0"), "PENDING");

        mockMvc.perform(post("/api/leave-requests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(annualLeaveTypeId, MON, MON, null, "HALF_DAY")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEAVE_REQUEST_OVERLAP"));
    }

    @Test
    void existingHalfDayBlocksAnotherHalfDayOnTheSameDate() throws Exception {
        LeaveTestFixtures.insertHalfDayLeaveRequest(jdbcTemplate, employeeId, annualLeaveTypeId, MON, "PENDING");

        mockMvc.perform(post("/api/leave-requests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(annualLeaveTypeId, MON, MON, null, "HALF_DAY")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEAVE_REQUEST_OVERLAP"));
    }

    @Test
    void approvedHalfDayBlocksANewFullDayCoveringThatDate() throws Exception {
        LeaveTestFixtures.insertHalfDayLeaveRequest(jdbcTemplate, employeeId, annualLeaveTypeId, MON, "APPROVED");

        mockMvc.perform(post("/api/leave-requests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(annualLeaveTypeId, MON, FRI, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEAVE_REQUEST_OVERLAP"));
    }

    @Test
    void rejectedHalfDayDoesNotBlockANewRequestOnTheDate() throws Exception {
        LeaveTestFixtures.insertHalfDayLeaveRequest(jdbcTemplate, employeeId, annualLeaveTypeId, MON, "REJECTED");

        mockMvc.perform(post("/api/leave-requests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(annualLeaveTypeId, MON, MON, null, "HALF_DAY")))
                .andExpect(status().isCreated());
    }

    // ---- Admin listing ----

    @Test
    void hrAdminCanListAllRequestsAndFilterByStatusAndEmployeeId() throws Exception {
        LeaveTestFixtures.insertLeaveRequest(
                jdbcTemplate, employeeId, annualLeaveTypeId, MON, FRI, new BigDecimal("5.0"), "PENDING");
        LeaveTestFixtures.insertLeaveRequest(
                jdbcTemplate, otherEmployeeId, annualLeaveTypeId, MON, FRI, new BigDecimal("5.0"), "APPROVED");

        // Deliberately >= rather than == 2 -- this runs against a shared real
        // Postgres database that may also hold unrelated leave requests
        // (e.g. demo data used for manual testing), so an unfiltered listing
        // must not assume it is otherwise empty. The employeeId-scoped
        // assertions below are what actually prove per-employee isolation.
        mockMvc.perform(get("/api/leave-requests").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));

        mockMvc.perform(get("/api/leave-requests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .param("status", "APPROVED")
                        .param("employeeId", String.valueOf(otherEmployeeId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].employeeId").value(otherEmployeeId));

        mockMvc.perform(get("/api/leave-requests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .param("employeeId", String.valueOf(employeeId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].employeeId").value(employeeId));
    }

    @Test
    void paginationIsSortedByCreatedAtDescThenIdDescDeterministically() throws Exception {
        LocalDateTime sameInstant = LocalDateTime.of(2026, 1, 1, 12, 0, 0);
        long earlierId = LeaveTestFixtures.insertLeaveRequestWithCreatedAt(
                jdbcTemplate, employeeId, annualLeaveTypeId, MON, FRI, new BigDecimal("5.0"), "PENDING", sameInstant);
        long laterId = LeaveTestFixtures.insertLeaveRequestWithCreatedAt(
                jdbcTemplate, employeeId, annualLeaveTypeId, NEXT_MON, NEXT_FRI, new BigDecimal("5.0"), "PENDING", sameInstant);

        // Scoped by employeeId so this test's fixed, deliberately old
        // sameInstant timestamp can't be out-ranked by unrelated newer rows
        // in the shared database (see the comment in
        // hrAdminCanListAllRequestsAndFilterByStatusAndEmployeeId) -- the
        // ORDER BY tiebreaker under test is unaffected by an added WHERE.
        mockMvc.perform(get("/api/leave-requests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .param("employeeId", String.valueOf(employeeId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(laterId))
                .andExpect(jsonPath("$.content[1].id").value(earlierId));
        assertThat(laterId).isGreaterThan(earlierId);
    }

    // ---- Detail (shared by both roles) ----

    @Test
    void hrAdminCanRetrieveAnyRequestById() throws Exception {
        long requestId = LeaveTestFixtures.insertLeaveRequest(
                jdbcTemplate, employeeId, annualLeaveTypeId, MON, FRI, new BigDecimal("5.0"), "PENDING");

        mockMvc.perform(get("/api/leave-requests/{id}", requestId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(requestId));
    }

    @Test
    void employeeCanRetrieveTheirOwnRequestById() throws Exception {
        long requestId = LeaveTestFixtures.insertLeaveRequest(
                jdbcTemplate, employeeId, annualLeaveTypeId, MON, FRI, new BigDecimal("5.0"), "PENDING");

        mockMvc.perform(get("/api/leave-requests/{id}", requestId).header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(requestId));
    }

    @Test
    void employeeReceives404ForAnotherEmployeesRequestById() throws Exception {
        long requestId = LeaveTestFixtures.insertLeaveRequest(
                jdbcTemplate, employeeId, annualLeaveTypeId, MON, FRI, new BigDecimal("5.0"), "PENDING");

        mockMvc.perform(get("/api/leave-requests/{id}", requestId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherEmployeeToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void unknownRequestIdReturns404() throws Exception {
        mockMvc.perform(get("/api/leave-requests/{id}", 999_999_999L).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }
}
