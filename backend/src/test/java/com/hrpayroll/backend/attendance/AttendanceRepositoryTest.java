package com.hrpayroll.backend.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hrpayroll.backend.employee.Employee;
import com.hrpayroll.backend.employee.EmployeeRepository;
import com.hrpayroll.backend.support.AttendanceTestFixtures;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Real-PostgreSQL JPA mapping/repository tests for {@link Attendance}, proving the V7 schema
 * (unchanged) already supports every Phase 9A foundation requirement. No service/controller layer
 * exists yet -- rows are constructed directly here exactly as a future AttendanceService would.
 */
@SpringBootTest
class AttendanceRepositoryTest {

    @Autowired
    private AttendanceRepository attendanceRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    private long departmentId;

    @BeforeEach
    void setUp() {
        AttendanceTestFixtures.cleanup(jdbcTemplate);
        departmentId = AttendanceTestFixtures.installDepartment(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        AttendanceTestFixtures.cleanup(jdbcTemplate);
    }

    private Employee newEmployee(String codeSuffix, String icNumber) {
        long employeeId = AttendanceTestFixtures.insertEmployee(jdbcTemplate, departmentId, codeSuffix, icNumber);
        return employeeRepository.findById(employeeId).orElseThrow();
    }

    @Test
    void validAttendanceRowPersistsSuccessfully() {
        Employee employee = newEmployee("A", "770101-01-0001");
        LocalDate workDate = LocalDate.of(2026, 1, 5);
        LocalDateTime clockInAt = LocalDateTime.of(2026, 1, 5, 9, 0);

        Attendance saved = attendanceRepository.saveAndFlush(new Attendance(employee, workDate, clockInAt, null));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getEmployee().getId()).isEqualTo(employee.getId());
        assertThat(saved.getWorkDate()).isEqualTo(workDate);
        assertThat(saved.getClockInAt()).isEqualTo(clockInAt);
        assertThat(saved.getClockOutAt()).isNull();
    }

    @Test
    void findByEmployeeIdAndWorkDateReturnsTheCorrectRow() {
        Employee employee = newEmployee("B", "770101-01-0002");
        LocalDate workDate = LocalDate.of(2026, 1, 6);
        LocalDateTime clockInAt = LocalDateTime.of(2026, 1, 6, 9, 0);
        Attendance saved = attendanceRepository.saveAndFlush(new Attendance(employee, workDate, clockInAt, null));
        entityManager.clear();

        Optional<Attendance> found = attendanceRepository.findByEmployeeIdAndWorkDate(employee.getId(), workDate);

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
        assertThat(found.get().getClockInAt()).isEqualTo(clockInAt);
    }

    @Test
    void findByEmployeeIdAndWorkDateForUnknownEmployeeOrDateReturnsEmpty() {
        Employee employee = newEmployee("C", "770101-01-0003");
        attendanceRepository.saveAndFlush(
                new Attendance(employee, LocalDate.of(2026, 1, 7), LocalDateTime.of(2026, 1, 7, 9, 0), null));
        entityManager.clear();

        assertThat(attendanceRepository.findByEmployeeIdAndWorkDate(employee.getId(), LocalDate.of(2026, 1, 8)))
                .isEmpty();
        assertThat(attendanceRepository.findByEmployeeIdAndWorkDate(-1L, LocalDate.of(2026, 1, 7))).isEmpty();
    }

    @Test
    void duplicateEmployeeAndWorkDateViolatesUniqueConstraint() {
        Employee employee = newEmployee("D", "770101-01-0004");
        LocalDate workDate = LocalDate.of(2026, 1, 9);
        attendanceRepository.saveAndFlush(
                new Attendance(employee, workDate, LocalDateTime.of(2026, 1, 9, 9, 0), null));

        Attendance duplicate = new Attendance(employee, workDate, LocalDateTime.of(2026, 1, 9, 10, 0), null);

        assertThatThrownBy(() -> attendanceRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(ex -> assertThat(extractConstraintName(ex)).isEqualTo("uq_attendance_employee_work_date"));
    }

    @Test
    void unknownEmployeeForeignKeyIsRejected() {
        Employee unknownEmployeeReference = entityManager.getReference(Employee.class, -1L);
        Attendance attendance = new Attendance(
                unknownEmployeeReference, LocalDate.of(2026, 1, 10), LocalDateTime.of(2026, 1, 10, 9, 0), null);

        assertThatThrownBy(() -> attendanceRepository.saveAndFlush(attendance))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void clockOutBeforeClockInViolatesCheckConstraint() {
        Employee employee = newEmployee("E", "770101-01-0005");
        LocalDateTime clockInAt = LocalDateTime.of(2026, 1, 11, 9, 0);
        LocalDateTime clockOutBeforeClockIn = clockInAt.minusMinutes(1);
        Attendance attendance = new Attendance(employee, LocalDate.of(2026, 1, 11), clockInAt, clockOutBeforeClockIn);

        assertThatThrownBy(() -> attendanceRepository.saveAndFlush(attendance))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(ex -> assertThat(extractConstraintName(ex))
                        .isEqualTo("chk_attendance_clock_out_after_clock_in"));
    }

    @Test
    void nullClockOutAtIsAccepted() {
        Employee employee = newEmployee("F", "770101-01-0006");

        Attendance saved = attendanceRepository.saveAndFlush(
                new Attendance(employee, LocalDate.of(2026, 1, 12), LocalDateTime.of(2026, 1, 12, 9, 0), null));

        assertThat(saved.getClockOutAt()).isNull();
    }

    @Test
    void clockOutAtEqualToClockInAtIsAccepted() {
        Employee employee = newEmployee("G", "770101-01-0007");
        LocalDateTime sameInstant = LocalDateTime.of(2026, 1, 13, 9, 0);

        Attendance saved = attendanceRepository.saveAndFlush(
                new Attendance(employee, LocalDate.of(2026, 1, 13), sameInstant, sameInstant));

        assertThat(saved.getClockOutAt()).isEqualTo(sameInstant);
    }

    @Test
    void historyQueryReturnsOnlyTheRequestedEmployeesRowsInNewestWorkDateFirstOrder() {
        Employee employeeA = newEmployee("H", "770101-01-0008");
        Employee employeeB = newEmployee("I", "770101-01-0009");
        attendanceRepository.saveAndFlush(
                new Attendance(employeeA, LocalDate.of(2026, 1, 14), LocalDateTime.of(2026, 1, 14, 9, 0), null));
        Attendance employeeALatest = attendanceRepository.saveAndFlush(
                new Attendance(employeeA, LocalDate.of(2026, 1, 16), LocalDateTime.of(2026, 1, 16, 9, 0), null));
        Attendance employeeAMiddle = attendanceRepository.saveAndFlush(
                new Attendance(employeeA, LocalDate.of(2026, 1, 15), LocalDateTime.of(2026, 1, 15, 9, 0), null));
        attendanceRepository.saveAndFlush(
                new Attendance(employeeB, LocalDate.of(2026, 1, 17), LocalDateTime.of(2026, 1, 17, 9, 0), null));
        entityManager.clear();

        Page<Attendance> page = attendanceRepository.findByEmployeeIdOrderByWorkDateDescIdDesc(
                employeeA.getId(), PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(a -> a.getEmployee().getId())
                .containsOnly(employeeA.getId());
        assertThat(page.getContent()).extracting(Attendance::getWorkDate)
                .containsExactly(
                        LocalDate.of(2026, 1, 16), LocalDate.of(2026, 1, 15), LocalDate.of(2026, 1, 14));
        assertThat(page.getContent().get(0).getId()).isEqualTo(employeeALatest.getId());
        assertThat(page.getContent().get(1).getId()).isEqualTo(employeeAMiddle.getId());
    }

    @Test
    void pageMetadataReflectsTotalElementsAndPageSizeAcrossPages() {
        Employee employee = newEmployee("J", "770101-01-0010");
        for (int day = 1; day <= 3; day++) {
            attendanceRepository.saveAndFlush(new Attendance(
                    employee, LocalDate.of(2026, 2, day), LocalDateTime.of(2026, 2, day, 9, 0), null));
        }
        entityManager.clear();

        Page<Attendance> firstPage = attendanceRepository.findByEmployeeIdOrderByWorkDateDescIdDesc(
                employee.getId(), PageRequest.of(0, 2));
        Page<Attendance> secondPage = attendanceRepository.findByEmployeeIdOrderByWorkDateDescIdDesc(
                employee.getId(), PageRequest.of(1, 2));

        assertThat(firstPage.getTotalElements()).isEqualTo(3);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(firstPage.getContent()).hasSize(2);
        assertThat(firstPage.getContent().get(0).getWorkDate()).isEqualTo(LocalDate.of(2026, 2, 3));
        assertThat(secondPage.getContent()).hasSize(1);
        assertThat(secondPage.getContent().get(0).getWorkDate()).isEqualTo(LocalDate.of(2026, 2, 1));
    }

    @Test
    void employeeDeletionRemainsRestrictedWhileAttendanceExists() {
        Employee employee = newEmployee("K", "770101-01-0011");
        attendanceRepository.saveAndFlush(
                new Attendance(employee, LocalDate.of(2026, 1, 18), LocalDateTime.of(2026, 1, 18, 9, 0), null));

        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM employees WHERE id = ?", employee.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
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
