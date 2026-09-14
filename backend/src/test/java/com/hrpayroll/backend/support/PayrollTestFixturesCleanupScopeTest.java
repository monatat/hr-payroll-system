package com.hrpayroll.backend.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Proves {@link PayrollTestFixtures#cleanup} never deletes a payroll run merely because its
 * period_year falls inside the reserved test-year band -- it must identify test-owned rows by
 * marker (fixture-admin-audited STARTED events, fixture employee-code prefix) or explicit id,
 * never by year range alone. Years 2000-2005/2099 are valid business years; "reserved for tests"
 * is not itself a safety boundary for deleting shared-database rows.
 */
@SpringBootTest
class PayrollTestFixturesCleanupScopeTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long sentinelRunId;

    /** The sentinel must never survive only because of an assertion failure above it -- removed unconditionally here. */
    @AfterEach
    void removeSentinelNoMatterWhat() {
        if (sentinelRunId != null) {
            jdbcTemplate.update("DELETE FROM payroll_runs WHERE id = ?", sentinelRunId);
            sentinelRunId = null;
        }
        PayrollTestFixtures.cleanup(jdbcTemplate);
    }

    @Test
    void cleanupNeverDeletesAnUnrelatedRunSharingTheReservedTestYearBand() {
        int sentinelMonth = 7;
        int sentinelYear = PayrollTestFixtures.CANDIDATE_TEST_PERIOD_YEAR_MIN;
        LocalDate periodStart = LocalDate.of(sentinelYear, sentinelMonth, 1);
        LocalDate periodEnd = periodStart.withDayOfMonth(periodStart.lengthOfMonth());
        // Simulates an unrelated real/manual payroll run: no fixture-admin STARTED audit, no fixture
        // employee payslip -- inserted directly, exactly as it would exist outside any test.
        sentinelRunId = jdbcTemplate.queryForObject(
                "INSERT INTO payroll_runs (period_month, period_year, period_start, period_end, status) "
                        + "VALUES (?, ?, ?, ?, 'PROCESSING') RETURNING id",
                Long.class,
                sentinelMonth,
                sentinelYear,
                periodStart,
                periodEnd);

        long departmentId = PayrollTestFixtures.installDepartment(jdbcTemplate);
        PayrollTestFixtures.installAdminUser(jdbcTemplate, passwordEncoder);
        PayrollTestFixtures.insertEmployee(jdbcTemplate, departmentId, "SCOPE", "000401010001");

        PayrollTestFixtures.cleanup(jdbcTemplate);

        Integer employeeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM employees WHERE employee_code LIKE ?",
                Integer.class,
                PayrollTestFixtures.EMPLOYEE_CODE_PREFIX + "%");
        assertThat(employeeCount).isZero();
        Integer adminCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, PayrollTestFixtures.ADMIN_EMAIL);
        assertThat(adminCount).isZero();
        Integer departmentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM departments WHERE name = ?", Integer.class, PayrollTestFixtures.DEPARTMENT_NAME);
        assertThat(departmentCount).isZero();

        Integer sentinelCount =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payroll_runs WHERE id = ?", Integer.class, sentinelRunId);
        assertThat(sentinelCount).isEqualTo(1);
    }
}
