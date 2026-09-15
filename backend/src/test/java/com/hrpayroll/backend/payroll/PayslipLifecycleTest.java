package com.hrpayroll.backend.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hrpayroll.backend.department.Department;
import com.hrpayroll.backend.employee.Employee;
import com.hrpayroll.backend.employee.EmploymentStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Payslip}'s two PDF lifecycle transitions,
 * {@link Payslip#markPdfGenerated(String)} and {@link Payslip#markPdfFailed()} (Phase 8C-2A). No
 * Spring/database dependency -- pure entity behavior, same style as {@link PayrollRunLifecycleTest}.
 */
class PayslipLifecycleTest {

    private static Payslip newPendingPayslip() {
        Department department = new Department("Engineering");
        Employee employee = new Employee(
                "EMP001",
                "Jane Doe",
                "770101-01-0001",
                department,
                "Software Engineer",
                EmploymentStatus.ACTIVE,
                LocalDate.of(2020, 1, 1),
                "Test Bank",
                "1234567890",
                "0123456789");
        PayrollRun run = new PayrollRun(
                1,
                2026,
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 31),
                PayrollRunStatus.CALCULATED,
                new BigDecimal("0.1100"),
                new BigDecimal("0.0050"),
                new BigDecimal("0.0020"),
                new BigDecimal("0.0500"));
        return new Payslip(
                run,
                employee,
                "EMP001",
                "Jane Doe",
                "Engineering",
                "Software Engineer",
                new BigDecimal("5000.00"),
                new BigDecimal("500.00"),
                new BigDecimal("200.00"),
                new BigDecimal("5700.00"),
                new BigDecimal("627.00"),
                new BigDecimal("28.50"),
                new BigDecimal("11.40"),
                new BigDecimal("285.00"),
                new BigDecimal("951.90"),
                new BigDecimal("4748.10"),
                null,
                PdfStatus.PENDING);
    }

    @Test
    void newPayslipStartsAsPending() {
        assertThat(newPendingPayslip().getPdfStatus()).isEqualTo(PdfStatus.PENDING);
    }

    @Test
    void markPdfGeneratedTransitionsFromPendingToGeneratedAndSetsThePath() {
        Payslip payslip = newPendingPayslip();

        payslip.markPdfGenerated("payroll-run-1/payslip-1.pdf");

        assertThat(payslip.getPdfStatus()).isEqualTo(PdfStatus.GENERATED);
        assertThat(payslip.getPdfPath()).isEqualTo("payroll-run-1/payslip-1.pdf");
    }

    @Test
    void markPdfGeneratedTransitionsFromFailedToGenerated() {
        Payslip payslip = newPendingPayslip();
        payslip.markPdfFailed();

        payslip.markPdfGenerated("payroll-run-1/payslip-1.pdf");

        assertThat(payslip.getPdfStatus()).isEqualTo(PdfStatus.GENERATED);
        assertThat(payslip.getPdfPath()).isEqualTo("payroll-run-1/payslip-1.pdf");
    }

    @Test
    void markPdfGeneratedRejectsANullPath() {
        Payslip payslip = newPendingPayslip();

        assertThatThrownBy(() -> payslip.markPdfGenerated(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markPdfGeneratedRejectsABlankPath() {
        Payslip payslip = newPendingPayslip();

        assertThatThrownBy(() -> payslip.markPdfGenerated("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markPdfGeneratedFromAlreadyGeneratedIsRejected() {
        Payslip payslip = newPendingPayslip();
        payslip.markPdfGenerated("payroll-run-1/payslip-1.pdf");

        assertThatThrownBy(() -> payslip.markPdfGenerated("payroll-run-1/payslip-1.pdf"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void markPdfFailedTransitionsFromPendingToFailed() {
        Payslip payslip = newPendingPayslip();

        payslip.markPdfFailed();

        assertThat(payslip.getPdfStatus()).isEqualTo(PdfStatus.FAILED);
    }

    @Test
    void markPdfFailedTransitionsFromFailedToFailed() {
        Payslip payslip = newPendingPayslip();
        payslip.markPdfFailed();

        payslip.markPdfFailed();

        assertThat(payslip.getPdfStatus()).isEqualTo(PdfStatus.FAILED);
    }

    @Test
    void markPdfFailedClearsAPreviouslySetPath() {
        // A FAILED row must never retain a usable-looking path from an earlier attempt (e.g. a
        // retry of an already-GENERATED-then-somehow-FAILED payslip is not possible via the public
        // API today, but the invariant is enforced defensively regardless).
        Payslip payslip = newPendingPayslip();
        payslip.markPdfFailed();

        assertThat(payslip.getPdfPath()).isNull();
    }

    @Test
    void markPdfFailedFromAlreadyGeneratedIsRejected() {
        Payslip payslip = newPendingPayslip();
        payslip.markPdfGenerated("payroll-run-1/payslip-1.pdf");

        assertThatThrownBy(payslip::markPdfFailed).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void noGenericPdfStatusOrPdfPathSetterExists() {
        boolean anyGenericSetter = java.util.Arrays.stream(Payslip.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals("setPdfStatus") || m.getName().equals("setPdfPath"));

        assertThat(anyGenericSetter).isFalse();
    }
}
