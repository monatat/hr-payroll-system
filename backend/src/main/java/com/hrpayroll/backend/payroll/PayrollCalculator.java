package com.hrpayroll.backend.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Pure, Spring/JPA/database/clock-free payroll calculation. Deductions are
 * applied to gross pay (an approved demonstration policy, not a fact
 * inferred from any schema). Each deduction is rounded independently,
 * immediately after its own multiplication, HALF_UP, to scale 2; the total
 * is the sum of those already-rounded deductions -- never gross pay
 * multiplied by one combined rate.
 */
public final class PayrollCalculator {

    private static final int SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private PayrollCalculator() {
    }

    public static PayrollCalculationResult calculate(
            BigDecimal basePay, BigDecimal housingAllowance, BigDecimal transportAllowance, PayrollRateSnapshot rates) {
        Objects.requireNonNull(basePay, "basePay must not be null");
        Objects.requireNonNull(housingAllowance, "housingAllowance must not be null");
        Objects.requireNonNull(transportAllowance, "transportAllowance must not be null");
        Objects.requireNonNull(rates, "rates must not be null");
        requireNonNegative(basePay, "basePay");
        requireNonNegative(housingAllowance, "housingAllowance");
        requireNonNegative(transportAllowance, "transportAllowance");

        BigDecimal normalizedBasePay = basePay.setScale(SCALE, ROUNDING);
        BigDecimal normalizedHousingAllowance = housingAllowance.setScale(SCALE, ROUNDING);
        BigDecimal normalizedTransportAllowance = transportAllowance.setScale(SCALE, ROUNDING);

        BigDecimal grossPay = normalizedBasePay
                .add(normalizedHousingAllowance)
                .add(normalizedTransportAllowance)
                .setScale(SCALE, ROUNDING);

        BigDecimal epfDeduction = round(grossPay.multiply(rates.epfRate()));
        BigDecimal socsoDeduction = round(grossPay.multiply(rates.socsoRate()));
        BigDecimal eisDeduction = round(grossPay.multiply(rates.eisRate()));
        BigDecimal incomeTax = round(grossPay.multiply(rates.incomeTaxRate()));

        BigDecimal totalDeductions = epfDeduction.add(socsoDeduction).add(eisDeduction).add(incomeTax);
        BigDecimal netPay = grossPay.subtract(totalDeductions);

        if (netPay.signum() < 0) {
            throw new IllegalStateException(
                    "Calculated net pay is negative (gross pay " + grossPay + ", total deductions " + totalDeductions + ")");
        }

        return new PayrollCalculationResult(
                normalizedBasePay,
                normalizedHousingAllowance,
                normalizedTransportAllowance,
                grossPay,
                epfDeduction,
                socsoDeduction,
                eisDeduction,
                incomeTax,
                totalDeductions,
                netPay);
    }

    private static BigDecimal round(BigDecimal value) {
        return value.setScale(SCALE, ROUNDING);
    }

    private static void requireNonNegative(BigDecimal value, String fieldName) {
        if (value.signum() < 0) {
            throw new IllegalArgumentException(fieldName + " must not be negative: was " + value);
        }
    }
}
