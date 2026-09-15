package com.hrpayroll.backend.payroll.pdf;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Pure, Spring-free BigDecimal-to-String currency formatter for payslip PDF rendering. Never
 * converts through {@code double}/{@code float} anywhere in its path -- normalization, rounding,
 * and thousands-grouping are all done directly on {@link BigDecimal#toPlainString()} output.
 * Negative values are supported for completeness even though persisted payroll monetary values
 * are always non-negative.
 */
public final class CurrencyFormatter {

    private static final String PREFIX = "RM ";
    private static final int SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private CurrencyFormatter() {
    }

    /** Formats {@code value} as e.g. {@code "RM 1,234.56"} (or {@code "-RM 1,234.56"} if negative). */
    public static String format(BigDecimal value) {
        Objects.requireNonNull(value, "value must not be null");

        BigDecimal normalized = value.setScale(SCALE, ROUNDING);
        boolean negative = normalized.signum() < 0;
        String plain = normalized.abs().toPlainString();

        int dotIndex = plain.indexOf('.');
        String integerPart = plain.substring(0, dotIndex);
        String fractionPart = plain.substring(dotIndex + 1);

        StringBuilder result = new StringBuilder();
        if (negative) {
            result.append('-');
        }
        result.append(PREFIX).append(groupThousands(integerPart)).append('.').append(fractionPart);
        return result.toString();
    }

    private static String groupThousands(String digits) {
        StringBuilder reversed = new StringBuilder();
        for (int i = digits.length() - 1, count = 0; i >= 0; i--, count++) {
            if (count > 0 && count % 3 == 0) {
                reversed.append(',');
            }
            reversed.append(digits.charAt(i));
        }
        return reversed.reverse().toString();
    }
}
