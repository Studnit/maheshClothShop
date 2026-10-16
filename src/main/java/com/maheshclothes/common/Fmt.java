package com.maheshclothes.common;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/** Display formatting used by templates as ${@fmt.money(x)}. Never changes stored values. */
@Component("fmt")
public class Fmt {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd-MM-yyyy hh:mm a");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("hh:mm a");

    private final ShopClock clock;

    public Fmt(ShopClock clock) {
        this.clock = clock;
    }

    public String money(Object v) {
        return IndianFormat.money(toDecimal(v));
    }

    public String amount(Object v) {
        return IndianFormat.amount(toDecimal(v));
    }

    public String number(Object v) {
        if (v == null) {
            return "0";
        }
        return IndianFormat.number(((Number) v).longValue());
    }

    /** Percent such as 5 or 12.5 without trailing zeros. */
    public String percent(Object v) {
        BigDecimal d = toDecimal(v).stripTrailingZeros();
        return d.scale() < 0 ? d.setScale(0).toPlainString() : d.toPlainString();
    }

    public String date(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof Instant i) {
            return DATE.format(clock.toLocal(i));
        }
        if (v instanceof LocalDate d) {
            return DATE.format(d);
        }
        return v.toString();
    }

    public String dateTime(Object v) {
        if (v instanceof Instant i) {
            return DATE_TIME.format(clock.toLocal(i));
        }
        return v == null ? "" : date(v);
    }

    public String time(Object v) {
        return v instanceof Instant i ? TIME.format(clock.toLocal(i)) : "";
    }

    /** ISO date for <input type="date"> values. */
    public String isoDate(Object v) {
        if (v instanceof Instant i) {
            return clock.toLocalDate(i).toString();
        }
        return v == null ? "" : v.toString();
    }

    /** "Red / M" style label; skips missing parts so templates never print the word "null". */
    public String variant(Object colour, Object size) {
        StringBuilder sb = new StringBuilder();
        if (colour != null && !colour.toString().isBlank()) {
            sb.append(colour);
        }
        if (size != null && !size.toString().isBlank()) {
            sb.append(sb.length() > 0 ? " / " : "").append(size);
        }
        return sb.toString();
    }

    private static BigDecimal toDecimal(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        if (v instanceof BigDecimal b) {
            return b;
        }
        return new BigDecimal(v.toString());
    }
}
