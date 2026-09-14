package com.hrpayroll.backend.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PayrollCalculator} and {@link PayrollRateSnapshot} --
 * pure, Spring/JPA/database-free value objects. Every fixed demonstration
 * rate used here is the approved flat, non-compliant, educational-only
 * figure; none of these are real Malaysian statutory rates.
 */
class PayrollCalculatorTest {

    private static final BigDecimal EPF_RATE = new BigDecimal("0.1100");
    private static final BigDecimal SOCSO_RATE = new BigDecimal("0.0050");
    private static final BigDecimal EIS_RATE = new BigDecimal("0.0020");
    private static final BigDecimal TAX_RATE = new BigDecimal("0.0500");

    private static PayrollRateSnapshot demoRates() {
        return new PayrollRateSnapshot(EPF_RATE, SOCSO_RATE, EIS_RATE, TAX_RATE);
    }

    @Test
    void workedExampleProducesExactExpectedAmounts() {
        PayrollCalculationResult result = PayrollCalculator.calculate(
                new BigDecimal("5000.00"), new BigDecimal("500.00"), new BigDecimal("200.00"), demoRates());

        assertThat(result.grossPay()).isEqualByComparingTo("5700.00");
        assertThat(result.epfDeduction()).isEqualByComparingTo("627.00");
        assertThat(result.socsoDeduction()).isEqualByComparingTo("28.50");
        assertThat(result.eisDeduction()).isEqualByComparingTo("11.40");
        assertThat(result.incomeTax()).isEqualByComparingTo("285.00");
        assertThat(result.totalDeductions()).isEqualByComparingTo("951.90");
        assertThat(result.netPay()).isEqualByComparingTo("4748.10");
    }

    @Test
    void grossPayIsSumOfBasePayAndAllowances() {
        PayrollCalculationResult result = PayrollCalculator.calculate(
                new BigDecimal("1000.00"), new BigDecimal("100.00"), new BigDecimal("50.00"), demoRates());

        assertThat(result.grossPay()).isEqualByComparingTo("1150.00");
    }

    @Test
    void epfRoundsHalfUpAtTheThirdDecimal() {
        PayrollRateSnapshot rates = new PayrollRateSnapshot(
                new BigDecimal("0.1250"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        PayrollCalculationResult result =
                PayrollCalculator.calculate(new BigDecimal("100.04"), BigDecimal.ZERO, BigDecimal.ZERO, rates);

        // 100.04 * 0.1250 = 12.505 exactly -- HALF_UP rounds to 12.51 (HALF_EVEN would give 12.50).
        assertThat(result.epfDeduction()).isEqualByComparingTo("12.51");
    }

    @Test
    void socsoRoundsHalfUpAtTheThirdDecimal() {
        PayrollRateSnapshot rates = new PayrollRateSnapshot(
                BigDecimal.ZERO, new BigDecimal("0.1250"), BigDecimal.ZERO, BigDecimal.ZERO);

        PayrollCalculationResult result =
                PayrollCalculator.calculate(new BigDecimal("100.04"), BigDecimal.ZERO, BigDecimal.ZERO, rates);

        assertThat(result.socsoDeduction()).isEqualByComparingTo("12.51");
    }

    @Test
    void eisRoundsHalfUpAtTheThirdDecimal() {
        PayrollRateSnapshot rates = new PayrollRateSnapshot(
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("0.1250"), BigDecimal.ZERO);

        PayrollCalculationResult result =
                PayrollCalculator.calculate(new BigDecimal("100.04"), BigDecimal.ZERO, BigDecimal.ZERO, rates);

        assertThat(result.eisDeduction()).isEqualByComparingTo("12.51");
    }

    @Test
    void incomeTaxRoundsHalfUpAtTheThirdDecimal() {
        PayrollRateSnapshot rates = new PayrollRateSnapshot(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("0.1250"));

        PayrollCalculationResult result =
                PayrollCalculator.calculate(new BigDecimal("100.04"), BigDecimal.ZERO, BigDecimal.ZERO, rates);

        assertThat(result.incomeTax()).isEqualByComparingTo("12.51");
    }

    @Test
    void totalDeductionsIsTheSumOfIndividuallyRoundedDeductionsNotOneCombinedRate() {
        // Each of two rates independently produces an exact .xx5 tie (100.04 * 0.1250 = 12.505),
        // each rounding UP to 12.51 on its own. Rounding the combined 0.2500 rate against gross pay
        // just once would instead give 100.04 * 0.2500 = 25.01 -- a different (wrong) result.
        PayrollRateSnapshot rates = new PayrollRateSnapshot(
                new BigDecimal("0.1250"), new BigDecimal("0.1250"), BigDecimal.ZERO, BigDecimal.ZERO);

        PayrollCalculationResult result =
                PayrollCalculator.calculate(new BigDecimal("100.04"), BigDecimal.ZERO, BigDecimal.ZERO, rates);

        assertThat(result.totalDeductions()).isEqualByComparingTo("25.02");
    }

    @Test
    void netPayIsGrossPayMinusTotalDeductionsExactly() {
        PayrollCalculationResult result = PayrollCalculator.calculate(
                new BigDecimal("5000.00"), new BigDecimal("500.00"), new BigDecimal("200.00"), demoRates());

        assertThat(result.netPay())
                .isEqualByComparingTo(result.grossPay().subtract(result.totalDeductions()));
    }

    @Test
    void zeroAllowancesLeaveGrossPayEqualToBasePay() {
        PayrollCalculationResult result =
                PayrollCalculator.calculate(new BigDecimal("3000.00"), BigDecimal.ZERO, BigDecimal.ZERO, demoRates());

        assertThat(result.grossPay()).isEqualByComparingTo("3000.00");
    }

    @Test
    void zeroRatesProduceZeroDeductionsAndNetPayEqualsGrossPay() {
        PayrollRateSnapshot zeroRates =
                new PayrollRateSnapshot(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        PayrollCalculationResult result =
                PayrollCalculator.calculate(new BigDecimal("2000.00"), BigDecimal.ZERO, BigDecimal.ZERO, zeroRates);

        assertThat(result.epfDeduction()).isEqualByComparingTo("0.00");
        assertThat(result.socsoDeduction()).isEqualByComparingTo("0.00");
        assertThat(result.eisDeduction()).isEqualByComparingTo("0.00");
        assertThat(result.incomeTax()).isEqualByComparingTo("0.00");
        assertThat(result.totalDeductions()).isEqualByComparingTo("0.00");
        assertThat(result.netPay()).isEqualByComparingTo("2000.00");
    }

    @Test
    void negativeBasePayIsRejected() {
        assertThatThrownBy(() -> PayrollCalculator.calculate(
                        new BigDecimal("-1.00"), BigDecimal.ZERO, BigDecimal.ZERO, demoRates()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("basePay");
    }

    @Test
    void negativeAllowanceIsRejected() {
        assertThatThrownBy(() -> PayrollCalculator.calculate(
                        new BigDecimal("1000.00"), new BigDecimal("-1.00"), BigDecimal.ZERO, demoRates()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("housingAllowance");

        assertThatThrownBy(() -> PayrollCalculator.calculate(
                        new BigDecimal("1000.00"), BigDecimal.ZERO, new BigDecimal("-1.00"), demoRates()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transportAllowance");
    }

    @Test
    void nullSalaryInputIsRejected() {
        assertThatThrownBy(() -> PayrollCalculator.calculate(null, BigDecimal.ZERO, BigDecimal.ZERO, demoRates()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("basePay");
        assertThatThrownBy(() -> PayrollCalculator.calculate(BigDecimal.ZERO, null, BigDecimal.ZERO, demoRates()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("housingAllowance");
        assertThatThrownBy(() -> PayrollCalculator.calculate(BigDecimal.ZERO, BigDecimal.ZERO, null, demoRates()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("transportAllowance");
    }

    @Test
    void nullRateSnapshotIsRejected() {
        assertThatThrownBy(() -> PayrollCalculator.calculate(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("rates");
    }

    @Test
    void nullIndividualRateIsRejected() {
        assertThatThrownBy(() -> new PayrollRateSnapshot(null, SOCSO_RATE, EIS_RATE, TAX_RATE))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("epfRate");
    }

    @Test
    void individualRateBelowZeroIsRejected() {
        assertThatThrownBy(() -> new PayrollRateSnapshot(new BigDecimal("-0.01"), SOCSO_RATE, EIS_RATE, TAX_RATE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("epfRate");
    }

    @Test
    void individualRateAboveOneIsRejected() {
        assertThatThrownBy(() -> new PayrollRateSnapshot(new BigDecimal("1.01"), SOCSO_RATE, EIS_RATE, TAX_RATE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("epfRate");
    }

    @Test
    void combinedRateAboveOneIsRejected() {
        assertThatThrownBy(() -> new PayrollRateSnapshot(
                        new BigDecimal("0.50"), new BigDecimal("0.30"), new BigDecimal("0.15"), new BigDecimal("0.10")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sum");
    }

    @Test
    void combinedRateExactlyOneIsAccepted() {
        PayrollRateSnapshot rates = new PayrollRateSnapshot(
                new BigDecimal("0.25"), new BigDecimal("0.25"), new BigDecimal("0.25"), new BigDecimal("0.25"));

        assertThat(rates.epfRate().add(rates.socsoRate()).add(rates.eisRate()).add(rates.incomeTaxRate()))
                .isEqualByComparingTo("1.00");
    }

    @Test
    void defensivelyRejectsAResultingNegativeNetPay() {
        // Four equal 0.25 rates sum to exactly 1.00 (allowed), but each deduction is rounded
        // independently and HALF_UP can round every one of them UP: 100.03 * 0.25 = 25.0075,
        // which rounds to 25.01 four times over = 100.04 in total deductions -- one cent more
        // than the 100.03 gross pay it was deducted from. netPay would be -0.01.
        PayrollRateSnapshot rates = new PayrollRateSnapshot(
                new BigDecimal("0.25"), new BigDecimal("0.25"), new BigDecimal("0.25"), new BigDecimal("0.25"));

        assertThatThrownBy(() ->
                        PayrollCalculator.calculate(new BigDecimal("100.03"), BigDecimal.ZERO, BigDecimal.ZERO, rates))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("net pay");
    }

    @Test
    void everyResultFieldIsNormalizedToScaleTwo() {
        PayrollCalculationResult result = PayrollCalculator.calculate(
                new BigDecimal("5000"), new BigDecimal("500"), new BigDecimal("200"), demoRates());

        assertThat(result.basePay().scale()).isEqualTo(2);
        assertThat(result.housingAllowance().scale()).isEqualTo(2);
        assertThat(result.transportAllowance().scale()).isEqualTo(2);
        assertThat(result.grossPay().scale()).isEqualTo(2);
        assertThat(result.epfDeduction().scale()).isEqualTo(2);
        assertThat(result.socsoDeduction().scale()).isEqualTo(2);
        assertThat(result.eisDeduction().scale()).isEqualTo(2);
        assertThat(result.incomeTax().scale()).isEqualTo(2);
        assertThat(result.totalDeductions().scale()).isEqualTo(2);
        assertThat(result.netPay().scale()).isEqualTo(2);
    }
}
