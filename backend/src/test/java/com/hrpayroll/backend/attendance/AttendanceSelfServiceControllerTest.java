package com.hrpayroll.backend.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hrpayroll.backend.security.jwt.JwtService;
import com.hrpayroll.backend.support.AttendanceTestFixtures;
import com.hrpayroll.backend.user.AccountStatus;
import com.hrpayroll.backend.user.User;
import com.hrpayroll.backend.user.UserRole;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Real-PostgreSQL controller/integration tests for {@code /api/employees/me/attendance}. Identity
 * comes only from {@code AuthenticatedUser} -- there is no {employeeId} path/query/body parameter
 * anywhere on any of these endpoints, so there is nothing for a client to spoof. Genuine
 * multi-thread concurrency races are already proven at the service layer in
 * {@link AttendanceConcurrencyTest}; these tests only prove the HTTP mapping of already-proven
 * service/exception behavior.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AttendanceSelfServiceControllerTest {

    private static final String BASE = "/api/employees/me/attendance";
    private static final String EMPLOYEE_EMAIL = "phase9b2.self-employee@test.local";
    private static final String OTHER_EMPLOYEE_EMAIL = "phase9b2.other-employee@test.local";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    private long departmentId;
    private long ownEmployeeId;
    private String employeeToken;
    private String adminToken;
    private String otherEmployeeToken;

    @BeforeEach
    void setUp() {
        AttendanceTestFixtures.cleanup(jdbcTemplate);
        jdbcTemplate.update("DELETE FROM users WHERE email IN (?, ?)", EMPLOYEE_EMAIL, OTHER_EMPLOYEE_EMAIL);
        departmentId = AttendanceTestFixtures.installDepartment(jdbcTemplate);
        long adminUserId = AttendanceTestFixtures.installAdminUser(jdbcTemplate, passwordEncoder);
        ownEmployeeId = AttendanceTestFixtures.insertEmployee(jdbcTemplate, departmentId, "SELF", "770101-01-0201");
        long otherEmployeeId = AttendanceTestFixtures.insertEmployee(jdbcTemplate, departmentId, "OTHER", "770101-01-0202");

        long employeeUserId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, role, account_status, employee_id) "
                        + "VALUES (?, ?, 'EMPLOYEE', 'ACTIVE', ?) RETURNING id",
                Long.class,
                EMPLOYEE_EMAIL,
                passwordEncoder.encode("Phase9B2SelfEmployeePass123!"),
                ownEmployeeId);
        long otherEmployeeUserId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, role, account_status, employee_id) "
                        + "VALUES (?, ?, 'EMPLOYEE', 'ACTIVE', ?) RETURNING id",
                Long.class,
                OTHER_EMPLOYEE_EMAIL,
                passwordEncoder.encode("Phase9B2OtherEmployeePass123!"),
                otherEmployeeId);

        adminToken = jwtService.generateAccessToken(
                new User(adminUserId, AttendanceTestFixtures.ADMIN_EMAIL, "hash", UserRole.HR_ADMIN, AccountStatus.ACTIVE, null));
        employeeToken = jwtService.generateAccessToken(
                new User(employeeUserId, EMPLOYEE_EMAIL, "hash", UserRole.EMPLOYEE, AccountStatus.ACTIVE, ownEmployeeId));
        otherEmployeeToken = jwtService.generateAccessToken(new User(
                otherEmployeeUserId, OTHER_EMPLOYEE_EMAIL, "hash", UserRole.EMPLOYEE, AccountStatus.ACTIVE, otherEmployeeId));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM users WHERE email IN (?, ?)", EMPLOYEE_EMAIL, OTHER_EMPLOYEE_EMAIL);
        AttendanceTestFixtures.cleanup(jdbcTemplate);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    // ---------------------------------------------------------------- authorization

    @Test
    void unauthenticatedClockInReturns401() throws Exception {
        mockMvc.perform(post(BASE + "/clock-in")).andExpect(status().isUnauthorized());
    }

    @Test
    void unauthenticatedClockOutReturns401() throws Exception {
        mockMvc.perform(post(BASE + "/clock-out")).andExpect(status().isUnauthorized());
    }

    @Test
    void unauthenticatedTodayReturns401() throws Exception {
        mockMvc.perform(get(BASE + "/today")).andExpect(status().isUnauthorized());
    }

    @Test
    void unauthenticatedHistoryReturns401() throws Exception {
        mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
    }

    @Test
    void hrAdminClockInReturns403() throws Exception {
        mockMvc.perform(post(BASE + "/clock-in").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void hrAdminClockOutReturns403() throws Exception {
        mockMvc.perform(post(BASE + "/clock-out").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void hrAdminTodayReturns403() throws Exception {
        mockMvc.perform(get(BASE + "/today").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void hrAdminHistoryReturns403() throws Exception {
        mockMvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void employeeCanAccessAllFourEndpoints() throws Exception {
        mockMvc.perform(post(BASE + "/clock-in").header(HttpHeaders.AUTHORIZATION, bearer(employeeToken)))
                .andExpect(status().isCreated());
        mockMvc.perform(get(BASE + "/today").header(HttpHeaders.AUTHORIZATION, bearer(employeeToken)))
                .andExpect(status().isOk());
        mockMvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer(employeeToken)))
                .andExpect(status().isOk());
        mockMvc.perform(post(BASE + "/clock-out").header(HttpHeaders.AUTHORIZATION, bearer(employeeToken)))
                .andExpect(status().isOk());
    }

    @Test
    void pathWithAnEmployeeIdSegmentDoesNotRouteToClockIn() throws Exception {
        mockMvc.perform(post(BASE + "/999/clock-in").header(HttpHeaders.AUTHORIZATION, bearer(employeeToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    void unlinkedEmployeePrincipalGetsTheExistingSafeEmployeeProfile404() throws Exception {
        // Deliberately never persisted to `users` -- the employee_id CHECK constraint makes a real
        // EMPLOYEE row with a null employee_id impossible, but a JWT is self-contained (no per-request
        // DB lookup), so this token alone exercises the defensive requireEmployeeId(principal) branch.
        String unlinkedToken = jwtService.generateAccessToken(
                new User(987654321L, "unlinked@test.local", "hash", UserRole.EMPLOYEE, AccountStatus.ACTIVE, null));

        mockMvc.perform(get(BASE + "/today").header(HttpHeaders.AUTHORIZATION, bearer(unlinkedToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Employee profile not found"));
    }

    @Test
    void employeeACannotReadOrMutateEmployeeBsAttendance() throws Exception {
        mockMvc.perform(post(BASE + "/clock-in").header(HttpHeaders.AUTHORIZATION, bearer(employeeToken)))
                .andExpect(status().isCreated());

        mockMvc.perform(get(BASE + "/today").header(HttpHeaders.AUTHORIZATION, bearer(otherEmployeeToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("NOT_CLOCKED_IN"));
        mockMvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer(otherEmployeeToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
        // Employee B has no open record of their own -- clocking out must fail with NO_OPEN_RECORD,
        // never accidentally close employee A's row.
        mockMvc.perform(post(BASE + "/clock-out").header(HttpHeaders.AUTHORIZATION, bearer(otherEmployeeToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ATTENDANCE_NO_OPEN_RECORD"));

        Integer employeeAOpenCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM attendance WHERE employee_id = ? AND clock_out_at IS NULL",
                Integer.class,
                ownEmployeeId);
        assertThat(employeeAOpenCount).isEqualTo(1);
    }

    // ---------------------------------------------------------------- clock-in

    @Test
    void firstClockInReturns201WithOnlyTheFourSafeFields() throws Exception {
        mockMvc.perform(post(BASE + "/clock-in").header(HttpHeaders.AUTHORIZATION, bearer(employeeToken)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.workDate").exists())
                .andExpect(jsonPath("$.clockInAt").exists())
                .andExpect(jsonPath("$.clockOutAt").doesNotExist())
                .andExpect(jsonPath("$.employeeId").doesNotExist())
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.employee").doesNotExist())
                .andExpect(jsonPath("$.department").doesNotExist())
                .andExpect(jsonPath("$.salary").doesNotExist())
                .andExpect(jsonPath("$.payroll").doesNotExist());
    }

    @Test
    void duplicateSameDayClockInReturnsStableConflict() throws Exception {
        mockMvc.perform(post(BASE + "/clock-in").header(HttpHeaders.AUTHORIZATION, bearer(employeeToken)))
                .andExpect(status().isCreated());

        mockMvc.perform(post(BASE + "/clock-in").header(HttpHeaders.AUTHORIZATION, bearer(employeeToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ATTENDANCE_ALREADY_CLOCKED_IN"))
                .andExpect(jsonPath("$.detail").value("You have already clocked in today."))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("constraint"))))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsStringIgnoringCase("sql"))));

        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM attendance WHERE employee_id = ?", Integer.class, ownEmployeeId);
        assertThat(rowCount).isEqualTo(1);
    }

    // ---------------------------------------------------------------- clock-out

    @Test
    void clockOutAfterClockInReturns200WithPopulatedClockOutAt() throws Exception {
        mockMvc.perform(post(BASE + "/clock-in").header(HttpHeaders.AUTHORIZATION, bearer(employeeToken)))
                .andExpect(status().isCreated());

        mockMvc.perform(post(BASE + "/clock-out").header(HttpHeaders.AUTHORIZATION, bearer(employeeToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clockOutAt").exists());
    }

    @Test
    void clockOutWithoutTodaysRowReturnsNoOpenRecord() throws Exception {
        mockMvc.perform(post(BASE + "/clock-out").header(HttpHeaders.AUTHORIZATION, bearer(employeeToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ATTENDANCE_NO_OPEN_RECORD"))
                .andExpect(jsonPath("$.detail").value("You have not clocked in today."));
    }

    @Test
    void repeatedClockOutReturnsAlreadyClockedOut() throws Exception {
        mockMvc.perform(post(BASE + "/clock-in").header(HttpHeaders.AUTHORIZATION, bearer(employeeToken)))
                .andExpect(status().isCreated());
        mockMvc.perform(post(BASE + "/clock-out").header(HttpHeaders.AUTHORIZATION, bearer(employeeToken)))
                .andExpect(status().isOk());

        mockMvc.perform(post(BASE + "/clock-out").header(HttpHeaders.AUTHORIZATION, bearer(employeeToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ATTENDANCE_ALREADY_CLOCKED_OUT"))
                .andExpect(jsonPath("$.detail").value("You have already clocked out today."));
    }

    @Test
    void staleProrDayOpenRowIsNotClosedByTodaysClockOutRequest() throws Exception {
        AttendanceTestFixtures.insertAttendance(
                jdbcTemplate, ownEmployeeId, LocalDate.now().minusDays(1), LocalDateTime.now().minusDays(1), null);
        mockMvc.perform(post(BASE + "/clock-in").header(HttpHeaders.AUTHORIZATION, bearer(employeeToken)))
                .andExpect(status().isCreated());

        mockMvc.perform(post(BASE + "/clock-out").header(HttpHeaders.AUTHORIZATION, bearer(employeeToken)))
                .andExpect(status().isOk());

        Integer staleStillOpen = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM attendance WHERE employee_id = ? AND work_date = ? AND clock_out_at IS NULL",
                Integer.class,
                ownEmployeeId,
                LocalDate.now().minusDays(1));
        assertThat(staleStillOpen).isEqualTo(1);
    }

    // ---------------------------------------------------------------- today status

    @Test
    void noRecordReturns200NotClockedInWithNullAttendance() throws Exception {
        mockMvc.perform(get(BASE + "/today").header(HttpHeaders.AUTHORIZATION, bearer(employeeToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("NOT_CLOCKED_IN"))
                .andExpect(jsonPath("$.attendance").doesNotExist());
    }

    @Test
    void openRecordReturns200ClockedIn() throws Exception {
        mockMvc.perform(post(BASE + "/clock-in").header(HttpHeaders.AUTHORIZATION, bearer(employeeToken)))
                .andExpect(status().isCreated());

        mockMvc.perform(get(BASE + "/today").header(HttpHeaders.AUTHORIZATION, bearer(employeeToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CLOCKED_IN"));
    }

    @Test
    void closedRecordReturns200ClockedOut() throws Exception {
        mockMvc.perform(post(BASE + "/clock-in").header(HttpHeaders.AUTHORIZATION, bearer(employeeToken)))
                .andExpect(status().isCreated());
        mockMvc.perform(post(BASE + "/clock-out").header(HttpHeaders.AUTHORIZATION, bearer(employeeToken)))
                .andExpect(status().isOk());

        mockMvc.perform(get(BASE + "/today").header(HttpHeaders.AUTHORIZATION, bearer(employeeToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CLOCKED_OUT"));
    }

    @Test
    void todayStatusIgnoresAStalePriorDayOpenRow() throws Exception {
        AttendanceTestFixtures.insertAttendance(
                jdbcTemplate, ownEmployeeId, LocalDate.now().minusDays(1), LocalDateTime.now().minusDays(1), null);

        mockMvc.perform(get(BASE + "/today").header(HttpHeaders.AUTHORIZATION, bearer(employeeToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("NOT_CLOCKED_IN"));
    }

    // ---------------------------------------------------------------- history

    @Test
    void historyDefaultsToPageZeroSizeTwenty() throws Exception {
        for (int day = 1; day <= 3; day++) {
            AttendanceTestFixtures.insertAttendance(
                    jdbcTemplate,
                    ownEmployeeId,
                    LocalDate.of(2026, 5, day),
                    LocalDateTime.of(2026, 5, day, 9, 0),
                    LocalDateTime.of(2026, 5, day, 18, 0));
        }

        mockMvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer(employeeToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(20))
                .andExpect(jsonPath("$.page.totalElements").value(3));
    }

    @Test
    void historyPaginationBoundariesAndTotalsAreCorrect() throws Exception {
        for (int day = 1; day <= 3; day++) {
            AttendanceTestFixtures.insertAttendance(
                    jdbcTemplate,
                    ownEmployeeId,
                    LocalDate.of(2026, 6, day),
                    LocalDateTime.of(2026, 6, day, 9, 0),
                    LocalDateTime.of(2026, 6, day, 18, 0));
        }

        mockMvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer(employeeToken)).param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.page.totalPages").value(2));
    }

    @Test
    void historyOrderingIsWorkDateDescWithIdDescTiebreaker() throws Exception {
        long latest = AttendanceTestFixtures.insertAttendance(
                jdbcTemplate,
                ownEmployeeId,
                LocalDate.of(2026, 7, 10),
                LocalDateTime.of(2026, 7, 10, 9, 0),
                LocalDateTime.of(2026, 7, 10, 18, 0));
        long middle = AttendanceTestFixtures.insertAttendance(
                jdbcTemplate,
                ownEmployeeId,
                LocalDate.of(2026, 7, 9),
                LocalDateTime.of(2026, 7, 9, 9, 0),
                LocalDateTime.of(2026, 7, 9, 18, 0));

        mockMvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer(employeeToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(latest))
                .andExpect(jsonPath("$.content[1].id").value(middle));
    }

    @Test
    void aClientSuppliedSortParameterCannotOverrideTheFixedAttendanceOrder() throws Exception {
        long latest = AttendanceTestFixtures.insertAttendance(
                jdbcTemplate,
                ownEmployeeId,
                LocalDate.of(2026, 8, 20),
                LocalDateTime.of(2026, 8, 20, 9, 0),
                LocalDateTime.of(2026, 8, 20, 18, 0));
        long earlier = AttendanceTestFixtures.insertAttendance(
                jdbcTemplate,
                ownEmployeeId,
                LocalDate.of(2026, 8, 19),
                LocalDateTime.of(2026, 8, 19, 9, 0),
                LocalDateTime.of(2026, 8, 19, 18, 0));

        // Attempt to force ascending order via a client-supplied sort param -- must have no effect.
        mockMvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer(employeeToken)).param("sort", "workDate,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(latest))
                .andExpect(jsonPath("$.content[1].id").value(earlier));
    }

    @Test
    void historyReturnsOnlyTheAuthenticatedEmployeesRecords() throws Exception {
        AttendanceTestFixtures.insertAttendance(
                jdbcTemplate,
                ownEmployeeId,
                LocalDate.of(2026, 9, 1),
                LocalDateTime.of(2026, 9, 1, 9, 0),
                LocalDateTime.of(2026, 9, 1, 18, 0));

        mockMvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer(otherEmployeeToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void historyContentContainsNoIdentityOrInternalFields() throws Exception {
        AttendanceTestFixtures.insertAttendance(
                jdbcTemplate,
                ownEmployeeId,
                LocalDate.of(2026, 10, 1),
                LocalDateTime.of(2026, 10, 1, 9, 0),
                LocalDateTime.of(2026, 10, 1, 18, 0));

        mockMvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer(employeeToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].employeeId").doesNotExist())
                .andExpect(jsonPath("$.content[0].employee").doesNotExist())
                .andExpect(jsonPath("$.content[0].userId").doesNotExist());
    }

    @Test
    void invalidPaginationFollowsExistingSafeConventionWithoutLeakingDetails() throws Exception {
        mockMvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer(employeeToken)).param("page", "-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.number").value(0));
    }
}
