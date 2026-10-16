package com.maheshclothes.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Money helpers. All rupee amounts use scale 2 and HALF_UP rounding; unit costs use scale 4. */
public final class Money {

    public static final BigDecimal ZERO = new BigDecimal("0.00");
    public static final BigDecimal HUNDRED = new BigDecimal("100");

    private Money() {
    }

    public static BigDecimal scale(BigDecimal v) {
        return v == null ? ZERO : v.setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal cost(BigDecimal v) {
        return v == null ? new BigDecimal("0.0000") : v.setScale(4, RoundingMode.HALF_UP);
    }

    public static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    public static boolean isPositive(BigDecimal v) {
        return v != null && v.signum() > 0;
    }

    public static BigDecimal of(String v) {
        return scale(new BigDecimal(v));
    }

    /** quantity x unit price (or cost), scaled to 2 places. */
    public static BigDecimal times(BigDecimal unit, int qty) {
        return scale(nz(unit).multiply(BigDecimal.valueOf(qty)));
    }
}
