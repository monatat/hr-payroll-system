package com.hrpayroll.backend.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hrpayroll.backend.support.AttendanceTestFixtures;
import com.hrpayroll.backend.support.MutableClock;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PagedModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

/**
 * Real-PostgreSQL service tests for {@link AttendanceService}, using a {@link MutableClock} so
 * every business date/timestamp assertion is deterministic and independent of the host machine's
 * timezone. Genuine multi-thread concurrency races live in {@link AttendanceConcurrencyTest}.
 */
@SpringBootTest
class AttendanceServiceTest {

    private static final ZoneId KL = ZoneId.of("Asia/Kuala_Lumpur");

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return new MutableClock(Instant.now(), KL);
        }
    }

    @Autowired
    private AttendanceService attendanceService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private Clock clock;

    private long departmentId;
    private long actorUserId;

    @BeforeEach
    void setUp() {
        AttendanceTestFixtures.cleanup(jdbcTemplate);
        departmentId = AttendanceTestFixtures.installDepartment(jdbcTemplate);
        actorUserId = AttendanceTestFixtures.installAdminUser(jdbcTemplate, passwordEncoder);
        setClockTo(LocalDateTime.of(2026, 3, 10, 9, 0));
    }

    @AfterEach
    void tearDown() {
        AttendanceTestFixtures.cleanup(jdbcTemplate);
    }

    private void setClockTo(LocalDateTime kualaLumpurLocalDateTime) {
        ((MutableClock) clock).set(kualaLumpurLocalDateTime.atZone(KL).toInstant());
    }

    private long newEmployee(String suffix, String ic) {
        return AttendanceTestFixtures.insertEmployee(jdbcTemplate, departmentId, suffix, ic);
    }

    private int countAttendanceRows(long employeeId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM attendance WHERE employee_id = ?", Integer.class, employeeId);
    }

    private int countAudit(String action, Long entityId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE entity_name = 'ATTENDANCE' AND entity_id = ? AND action = ?",
                Integer.class,
                entityId,
                action);
    }

    private int countAuditForAction(String action) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE entity_name = 'ATTENDANCE' AND action = ?", Integer.class, action);
    }

    // ---------------------------------------------------------------- clock-in

    @Test
    void successfulFirstClockInPersistsRowWithClockDerivedValuesAndCorrectEmployee() {
        long employeeId = newEmployee("A", "770101-01-0001");

        AttendanceResponse response = attendanceService.clockIn(employeeId, actorUserId);

        assertThat(response.workDate()).isEqualTo(LocalDate.of(2026, 3, 10));
        assertThat(response.clockInAt()).isEqualTo(LocalDateTime.of(2026, 3, 10, 9, 0));
        assertThat(response.clockOutAt()).isNull();
        Long storedEmployeeId = jdbcTemplate.queryForObject(
                "SELECT employee_id FROM attendance WHERE id = ?", Long.class, response.id());
        assertThat(storedEmployeeId).isEqualTo(employeeId);
    }

    @Test
    void successfulClockInRecordsExactlyOneAuditEventWithSafeDetails() {
        long employeeId = newEmployee("B", "770101-01-0002");

        AttendanceResponse response = attendanceService.clockIn(employeeId, actorUserId);

        assertThat(countAudit("ATTENDANCE_CLOCKED_IN", response.id())).isEqualTo(1);
        String actorId = jdbcTemplate.queryForObject(
                "SELECT actor_id::text FROM audit_logs WHERE entity_name = 'ATTENDANCE' AND entity_id = ? AND action = 'ATTENDANCE_CLOCKED_IN'",
                String.class,
                response.id());
        assertThat(actorId).isEqualTo(String.valueOf(actorUserId));
        String workDateDetail = jdbcTemplate.queryForObject(
                "SELECT details->>'workDate' FROM audit_logs WHERE entity_name = 'ATTENDANCE' AND entity_id = ? AND action = 'ATTENDANCE_CLOCKED_IN'",
                String.class,
                response.id());
        assertThat(workDateDetail).isEqualTo("2026-03-10");
    }

    @Test
    void secondClockInOnTheSameBusinessDateFailsThroughTheUniqueConstraint() {
        long employeeId = newEmployee("C", "770101-01-0003");
        attendanceService.clockIn(employeeId, actorUserId);

        assertThatThrownBy(() -> attendanceService.clockIn(employeeId, actorUserId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void duplicateClockInAttemptLeavesExactlyOneAttendanceRow() {
        long employeeId = newEmployee("D", "770101-01-0004");
        attendanceService.clockIn(employeeId, actorUserId);

        try {
            attendanceService.clockIn(employeeId, actorUserId);
        } catch (RuntimeException ignored) {
            // expected -- asserted separately above
        }

        assertThat(countAttendanceRows(employeeId)).isEqualTo(1);
    }

    @Test
    void duplicateClockInAttemptAddsNoSecondSuccessAudit() {
        long employeeId = newEmployee("E", "770101-01-0005");
        AttendanceResponse first = attendanceService.clockIn(employeeId, actorUserId);

        try {
            attendanceService.clockIn(employeeId, actorUserId);
        } catch (RuntimeException ignored) {
            // expected
        }

        assertThat(countAudit("ATTENDANCE_CLOCKED_IN", first.id())).isEqualTo(1);
    }

    @Test
    void clockInForUnknownEmployeeFailsSafelyAndCreatesNoAttendanceOrAuditRow() {
        int auditBefore = countAuditForAction("ATTENDANCE_CLOCKED_IN");

        assertThatThrownBy(() -> attendanceService.clockIn(-1L, actorUserId))
                .isInstanceOf(ResponseStatusException.class);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM attendance", Integer.class))
                .isEqualTo(0);
        assertThat(countAuditForAction("ATTENDANCE_CLOCKED_IN")).isEqualTo(auditBefore);
    }

    // ---------------------------------------------------------------- clock-out

    @Test
    void successfulClockOutUpdatesTodaysRowWithClockDerivedTime() {
        long employeeId = newEmployee("F", "770101-01-0006");
        attendanceService.clockIn(employeeId, actorUserId);
        setClockTo(LocalDateTime.of(2026, 3, 10, 18, 0));

        AttendanceResponse response = attendanceService.clockOut(employeeId, actorUserId);

        assertThat(response.clockOutAt()).isEqualTo(LocalDateTime.of(2026, 3, 10, 18, 0));
        assertThat(response.workDate()).isEqualTo(LocalDate.of(2026, 3, 10));
    }

    @Test
    void successfulClockOutProducesExactlyOneAuditEvent() {
        long employeeId = newEmployee("G", "770101-01-0007");
        attendanceService.clockIn(employeeId, actorUserId);
        setClockTo(LocalDateTime.of(2026, 3, 10, 18, 0));

        AttendanceResponse response = attendanceService.clockOut(employeeId, actorUserId);

        assertThat(countAudit("ATTENDANCE_CLOCKED_OUT", response.id())).isEqualTo(1);
        String clockOutDetail = jdbcTemplate.queryForObject(
                "SELECT details->>'clockOutAt' FROM audit_logs WHERE entity_name = 'ATTENDANCE' AND entity_id = ? AND action = 'ATTENDANCE_CLOCKED_OUT'",
                String.class,
                response.id());
        assertThat(clockOutDetail).contains("2026-03-10T18:00");
    }

    @Test
    void clockOutWithNoTodayRowThrowsNoOpenRecord() {
        long employeeId = newEmployee("H", "770101-01-0008");

        assertThatThrownBy(() -> attendanceService.clockOut(employeeId, actorUserId))
                .isInstanceOf(AttendanceNoOpenRecordException.class)
                .hasMessage("You have not clocked in today.");
    }

    @Test
    void clockOutOnAnAlreadyClosedRowThrowsAlreadyClockedOut() {
        long employeeId = newEmployee("I", "770101-01-0009");
        attendanceService.clockIn(employeeId, actorUserId);
        attendanceService.clockOut(employeeId, actorUserId);

        assertThatThrownBy(() -> attendanceService.clockOut(employeeId, actorUserId))
                .isInstanceOf(AttendanceAlreadyClockedOutException.class)
                .hasMessage("You have already clocked out today.");
    }

    @Test
    void repeatedClockOutDoesNotChangeTheOriginalClockOutAt() {
        long employeeId = newEmployee("J", "770101-01-0010");
        attendanceService.clockIn(employeeId, actorUserId);
        AttendanceResponse first = attendanceService.clockOut(employeeId, actorUserId);
        setClockTo(LocalDateTime.of(2026, 3, 10, 23, 0));

        try {
            attendanceService.clockOut(employeeId, actorUserId);
        } catch (AttendanceAlreadyClockedOutException ignored) {
            // expected
        }

        LocalDateTime stored = jdbcTemplate.queryForObject(
                "SELECT clock_out_at FROM attendance WHERE id = ?", LocalDateTime.class, first.id());
        assertThat(stored).isEqualTo(first.clockOutAt());
    }

    @Test
    void staleprioDayOpenRowIsNotClosedByTodaysClockOut() {
        long employeeId = newEmployee("K", "770101-01-0011");
        setClockTo(LocalDateTime.of(2026, 3, 9, 9, 0));
        AttendanceResponse yesterday = attendanceService.clockIn(employeeId, actorUserId);
        setClockTo(LocalDateTime.of(2026, 3, 10, 9, 0));
        attendanceService.clockIn(employeeId, actorUserId);

        attendanceService.clockOut(employeeId, actorUserId);

        LocalDateTime yesterdayClockOut = jdbcTemplate.queryForObject(
                "SELECT clock_out_at FROM attendance WHERE id = ?", LocalDateTime.class, yesterday.id());
        assertThat(yesterdayClockOut).isNull();
    }

    @Test
    void stalePriorDayOpenRowDoesNotPreventTodaysClockIn() {
        long employeeId = newEmployee("L", "770101-01-0012");
        setClockTo(LocalDateTime.of(2026, 3, 9, 9, 0));
        attendanceService.clockIn(employeeId, actorUserId);
        setClockTo(LocalDateTime.of(2026, 3, 10, 9, 0));

        AttendanceResponse today = attendanceService.clockIn(employeeId, actorUserId);

        assertThat(today.workDate()).isEqualTo(LocalDate.of(2026, 3, 10));
        assertThat(countAttendanceRows(employeeId)).isEqualTo(2);
    }

    @Test
    void failedClockOutProducesNoSuccessAudit() {
        long employeeId = newEmployee("M", "770101-01-0013");
        int auditBefore = countAuditForAction("ATTENDANCE_CLOCKED_OUT");

        try {
            attendanceService.clockOut(employeeId, actorUserId);
        } catch (AttendanceNoOpenRecordException ignored) {
            // expected
        }

        assertThat(countAuditForAction("ATTENDANCE_CLOCKED_OUT")).isEqualTo(auditBefore);
    }

    // ---------------------------------------------------------------- today status

    @Test
    void noTodayRowReturnsNotClockedInWithNullAttendance() {
        long employeeId = newEmployee("N", "770101-01-0014");

        AttendanceTodayResponse status = attendanceService.getTodayStatus(employeeId);

        assertThat(status.state()).isEqualTo(AttendanceTodayStatus.NOT_CLOCKED_IN);
        assertThat(status.attendance()).isNull();
    }

    @Test
    void openTodayRowReturnsClockedIn() {
        long employeeId = newEmployee("O", "770101-01-0015");
        attendanceService.clockIn(employeeId, actorUserId);

        AttendanceTodayResponse status = attendanceService.getTodayStatus(employeeId);

        assertThat(status.state()).isEqualTo(AttendanceTodayStatus.CLOCKED_IN);
        assertThat(status.attendance()).isNotNull();
    }

    @Test
    void closedTodayRowReturnsClockedOut() {
        long employeeId = newEmployee("P", "770101-01-0016");
        attendanceService.clockIn(employeeId, actorUserId);
        attendanceService.clockOut(employeeId, actorUserId);

        AttendanceTodayResponse status = attendanceService.getTodayStatus(employeeId);

        assertThat(status.state()).isEqualTo(AttendanceTodayStatus.CLOCKED_OUT);
        assertThat(status.attendance().clockOutAt()).isNotNull();
    }

    @Test
    void oldOpenRowsDoNotAffectTodaysStatus() {
        long employeeId = newEmployee("Q", "770101-01-0017");
        setClockTo(LocalDateTime.of(2026, 3, 9, 9, 0));
        attendanceService.clockIn(employeeId, actorUserId);
        setClockTo(LocalDateTime.of(2026, 3, 10, 9, 0));

        AttendanceTodayResponse status = attendanceService.getTodayStatus(employeeId);

        assertThat(status.state()).isEqualTo(AttendanceTodayStatus.NOT_CLOCKED_IN);
    }

    // ---------------------------------------------------------------- history

    @Test
    void historyReturnsOnlyTheRequestedEmployeesOwnRows() {
        long employeeA = newEmployee("R", "770101-01-0018");
        long employeeB = newEmployee("S", "770101-01-0019");
        attendanceService.clockIn(employeeA, actorUserId);
        setClockTo(LocalDateTime.of(2026, 3, 10, 9, 30));
        attendanceService.clockIn(employeeB, actorUserId);

        PagedModel<AttendanceResponse> history = attendanceService.listHistory(employeeA, PageRequest.of(0, 10));

        assertThat(history.getContent()).hasSize(1);
    }

    @Test
    void historyOrderingIsWorkDateDescThenIdDesc() {
        long employeeId = newEmployee("T", "770101-01-0020");
        setClockTo(LocalDateTime.of(2026, 3, 8, 9, 0));
        attendanceService.clockIn(employeeId, actorUserId);
        setClockTo(LocalDateTime.of(2026, 3, 10, 9, 0));
        AttendanceResponse latest = attendanceService.clockIn(employeeId, actorUserId);
        setClockTo(LocalDateTime.of(2026, 3, 9, 9, 0));
        AttendanceResponse middle = attendanceService.clockIn(employeeId, actorUserId);

        PagedModel<AttendanceResponse> history = attendanceService.listHistory(employeeId, PageRequest.of(0, 10));

        List<AttendanceResponse> content = List.copyOf(history.getContent());
        assertThat(content.get(0).id()).isEqualTo(latest.id());
        assertThat(content.get(1).id()).isEqualTo(middle.id());
    }

    @Test
    void historyPaginationContentAndMetadataAreCorrect() {
        long employeeId = newEmployee("U", "770101-01-0021");
        for (int day = 1; day <= 3; day++) {
            setClockTo(LocalDateTime.of(2026, 4, day, 9, 0));
            attendanceService.clockIn(employeeId, actorUserId);
        }

        PagedModel<AttendanceResponse> firstPage = attendanceService.listHistory(employeeId, PageRequest.of(0, 2));

        assertThat(firstPage.getMetadata()).isNotNull();
        assertThat(firstPage.getMetadata().totalElements()).isEqualTo(3);
        assertThat(firstPage.getContent()).hasSize(2);
    }

    @Test
    void attendanceResponseExposesNoEmployeeIdentityField() {
        List<String> componentNames = Arrays.stream(AttendanceResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(componentNames)
                .containsExactlyInAnyOrder("id", "workDate", "clockInAt", "clockOutAt")
                .noneMatch(name -> name.toLowerCase().contains("employee"))
                .noneMatch(name -> name.toLowerCase().contains("user"));
    }

    // ---------------------------------------------------------------- timezone / boundary

    @Test
    void clockImmediatelyBeforeKualaLumpurMidnightUsesTheEarlierBusinessDate() {
        long employeeId = newEmployee("V", "770101-01-0022");
        setClockTo(LocalDateTime.of(2026, 3, 10, 23, 59, 59));

        AttendanceResponse response = attendanceService.clockIn(employeeId, actorUserId);

        assertThat(response.workDate()).isEqualTo(LocalDate.of(2026, 3, 10));
    }

    @Test
    void clockImmediatelyAfterKualaLumpurMidnightUsesTheNextBusinessDate() {
        long employeeId = newEmployee("W", "770101-01-0023");
        setClockTo(LocalDateTime.of(2026, 3, 11, 0, 0, 1));

        AttendanceResponse response = attendanceService.clockIn(employeeId, actorUserId);

        assertThat(response.workDate()).isEqualTo(LocalDate.of(2026, 3, 11));
    }

    @Test
    void hostDefaultTimezoneDoesNotAffectTheCalculatedBusinessDate() {
        long employeeId = newEmployee("X", "770101-01-0024");
        // 2026-03-10 23:30 in Kuala Lumpur is already 2026-03-11 in a host default far enough east
        // (e.g. Tokyo, UTC+9 vs UTC+8) -- if the service ever fell back to the host default instead
        // of the injected Clock's zone, this would silently compute the wrong business date.
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
            setClockTo(LocalDateTime.of(2026, 3, 10, 23, 30));

            AttendanceResponse response = attendanceService.clockIn(employeeId, actorUserId);

            assertThat(response.workDate()).isEqualTo(LocalDate.of(2026, 3, 10));
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
