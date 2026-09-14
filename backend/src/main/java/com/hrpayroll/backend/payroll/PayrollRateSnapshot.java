package com.hrpayroll.backend.payroll;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Immutable snapshot of the four fixed demonstration deduction rates used by
 * one payroll run. These are flat, educational-only figures -- they are not
 * legally accurate Malaysian EPF/SOCSO/EIS/income-tax rates (which are
 * tiered and wage-band-based in reality).
 */
public final class PayrollRateSnapshot {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;

    private final BigDecimal epfRate;
    private final BigDecimal socsoRate;
    private final BigDecimal eisRate;
    private final BigDecimal incomeTaxRate;

    public PayrollRateSnapshot(BigDecimal epfRate, BigDecimal socsoRate, BigDecimal eisRate, BigDecimal incomeTaxRate) {
        this.epfRate = requireValidRate(epfRate, "epfRate");
        this.socsoRate = requireValidRate(socsoRate, "socsoRate");
        this.eisRate = requireValidRate(eisRate, "eisRate");
        this.incomeTaxRate = requireValidRate(incomeTaxRate, "incomeTaxRate");

        BigDecimal sum = this.epfRate.add(this.socsoRate).add(this.eisRate).add(this.incomeTaxRate);
        if (sum.compareTo(ONE) > 0) {
            throw new IllegalArgumentException("The sum of all four rates must not exceed 1: was " + sum);
        }
    }

    /** The approved fixed demonstration rates: EPF 11.00%, SOCSO 0.50%, EIS 0.20%, income tax 5.00%. Demo-only, not statutory. */
    public static PayrollRateSnapshot demoDefaults() {
        return new PayrollRateSnapshot(
                new BigDecimal("0.1100"), new BigDecimal("0.0050"), new BigDecimal("0.0020"), new BigDecimal("0.0500"));
    }

    private static BigDecimal requireValidRate(BigDecimal rate, String fieldName) {
        Objects.requireNonNull(rate, fieldName + " must not be null");
        if (rate.compareTo(ZERO) < 0 || rate.compareTo(ONE) > 0) {
            throw new IllegalArgumentException(fieldName + " must be between 0 and 1 inclusive: was " + rate);
        }
        return rate;
    }

    public BigDecimal epfRate() {
        return epfRate;
    }

    public BigDecimal socsoRate() {
        return socsoRate;
    }

    public BigDecimal eisRate() {
        return eisRate;
    }

    public BigDecimal incomeTaxRate() {
        return incomeTaxRate;
    }
}
