package com.hrpayroll.backend.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.hrpayroll.backend.department.Department;
import com.hrpayroll.backend.employee.Employee;
import com.hrpayroll.backend.employee.EmploymentStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for the general-purpose {@link LeaveBalance} creation constructor (Phase 12C-2): the
 * constructor itself enforces the balance invariants the schema cannot express (non-negative days,
 * used never above entitled, a sensible year), so every caller -- not just the demo seed -- gets a
 * schema-valid, semantically valid balance.
 */
class LeaveBalanceTest {

    private final Employee employee = new Employee(
            "LB-UNIT-1", "Unit Test Employee", "LB-UNIT-IC-1", new Department("Unit Dept"), "Tester",
            EmploymentStatus.ACTIVE, LocalDate.of(2024, 1, 2), null, null, null);
    private final LeaveType leaveType = mock(LeaveType.class);

    @Test
    void createsAValidBalanceWithTheGivenValues() {
        LeaveBalance balance = new LeaveBalance(employee, leaveType, 2026, new BigDecimal("14.0"), new BigDecimal("2.0"));

        assertThat(balance.getEmployee()).isSameAs(employee);
        assertThat(balance.getLeaveType()).isSameAs(leaveType);
        assertThat(balance.getYear()).isEqualTo(2026);
        assertThat(balance.getEntitledDays()).isEqualByComparingTo("14.0");
        assertThat(balance.getUsedDays()).isEqualByComparingTo("2.0");
        assertThat(balance.getId()).isNull();
    }

    @Test
    void allowsZeroUsedAndUsedEqualToEntitled() {
        assertThat(new LeaveBalance(employee, leaveType, 2026, new BigDecimal("14.0"), BigDecimal.ZERO).getUsedDays())
                .isEqualByComparingTo("0");
        assertThat(new LeaveBalance(employee, leaveType, 2026, new BigDecimal("12.0"), new BigDecimal("12.0")).getUsedDays())
                .isEqualByComparingTo("12.0");
    }

    @Test
    void rejectsANullEmployee() {
        assertThatThrownBy(() -> new LeaveBalance(null, leaveType, 2026, new BigDecimal("14.0"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsANullLeaveType() {
        assertThatThrownBy(() -> new LeaveBalance(employee, null, 2026, new BigDecimal("14.0"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAnOutOfRangeYear() {
        assertThatThrownBy(() -> new LeaveBalance(employee, leaveType, 1999, new BigDecimal("14.0"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LeaveBalance(employee, leaveType, 2101, new BigDecimal("14.0"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LeaveBalance(employee, leaveType, 0, new BigDecimal("14.0"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullDayValues() {
        assertThatThrownBy(() -> new LeaveBalance(employee, leaveType, 2026, null, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LeaveBalance(employee, leaveType, 2026, new BigDecimal("14.0"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeEntitledDays() {
        assertThatThrownBy(() -> new LeaveBalance(employee, leaveType, 2026, new BigDecimal("-1.0"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeUsedDays() {
        assertThatThrownBy(() -> new LeaveBalance(employee, leaveType, 2026, new BigDecimal("14.0"), new BigDecimal("-0.5")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUsedDaysGreaterThanEntitled() {
        assertThatThrownBy(() -> new LeaveBalance(employee, leaveType, 2026, new BigDecimal("14.0"), new BigDecimal("14.5")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
