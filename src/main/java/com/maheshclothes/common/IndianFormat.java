package com.maheshclothes.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Indian digit grouping (12,34,567.50) which java.text.DecimalFormat cannot produce. */
public final class IndianFormat {

    private IndianFormat() {
    }

    /** e.g. 1234567.5 -> "12,34,567.50" (no currency symbol). */
    public static String amount(BigDecimal value) {
        BigDecimal v = value == null ? BigDecimal.ZERO : value;
        v = v.setScale(2, RoundingMode.HALF_UP);
        boolean negative = v.signum() < 0;
        String plain = v.abs().toPlainString();
        int dot = plain.indexOf('.');
        String whole = dot < 0 ? plain : plain.substring(0, dot);
        String frac = dot < 0 ? "00" : plain.substring(dot + 1);
        String out = group(whole) + "." + frac;
        return negative ? "-" + out : out;
    }

    /** e.g. "₹12,34,567.50". */
    public static String money(BigDecimal value) {
        String a = amount(value);
        return a.startsWith("-") ? "-₹" + a.substring(1) : "₹" + a;
    }

    /** Whole numbers with Indian grouping, e.g. 1234567 -> "12,34,567". */
    public static String number(long value) {
        boolean negative = value < 0;
        String s = group(Long.toString(Math.abs(value)));
        return negative ? "-" + s : s;
    }

    static String group(String digits) {
        int n = digits.length();
        if (n <= 3) {
            return digits;
        }
        String last3 = digits.substring(n - 3);
        String rest = digits.substring(0, n - 3);
        StringBuilder sb = new StringBuilder();
        int first = rest.length() % 2;
        if (first > 0) {
            sb.append(rest, 0, first);
        }
        for (int i = first; i < rest.length(); i += 2) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(rest, i, i + 2);
        }
        return sb + "," + last3;
    }
}
