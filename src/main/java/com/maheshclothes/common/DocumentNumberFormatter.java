package com.maheshclothes.common;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Builds document numbers from an owner-configurable pattern.
 * Tokens: {PREFIX} {FY} (2026-27) {FYS} (2627) {YYYY} {YY} {YYYYMMDD} {HHMMSS} {SEQ}.
 * A pattern with {HHMMSS} (for example MCS-{YYYYMMDD}-{HHMMSS}) needs no {SEQ}: a second bill created in the
 * same second simply gets "-2" added, so numbers stay unique.
 */
public final class DocumentNumberFormatter {

    /** GST rules limit an invoice number to 16 characters (letters, digits, "/" and "-"). */
    public static final int GST_MAX_LENGTH = 16;

    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HHmmss");

    private DocumentNumberFormatter() {
    }

    /** Date-and-time aware version used for sales invoices. */
    public static String format(String pattern, String prefix, LocalDateTime when, long seq, int padding) {
        if (pattern != null && pattern.contains("{HHMMSS}")) {
            String p = pattern.replace("{HHMMSS}", HMS.format(when));
            if (p.contains("{SEQ}")) {
                return format(p, prefix, when, seq, padding);
            }
            String base = fill(p, prefix, when);
            return seq > 1 ? base + "-" + seq : base;
        }
        return format(pattern, prefix, when.toLocalDate(), seq, padding);
    }

    public static String format(String pattern, String prefix, LocalDate date, long seq, int padding) {
        if (pattern == null || !pattern.contains("{SEQ}")) {
            throw new BusinessException("settings.error.invoiceformat");
        }
        int pad = Math.max(1, Math.min(10, padding));
        return fill(pattern, prefix, date.atStartOfDay()).replace("{SEQ}", String.format("%0" + pad + "d", seq));
    }

    private static String fill(String pattern, String prefix, LocalDateTime when) {
        LocalDate date = when.toLocalDate();
        return pattern
                .replace("{PREFIX}", prefix == null ? "" : prefix)
                .replace("{YYYYMMDD}", YMD.format(date))
                .replace("{FYS}", FinancialYear.shortLabel(date))
                .replace("{FY}", FinancialYear.label(date))
                .replace("{YYYY}", String.valueOf(date.getYear()))
                .replace("{YY}", String.format("%02d", date.getYear() % 100));
    }

    public static boolean exceedsGstLimit(String formatted) {
        return formatted != null && formatted.length() > GST_MAX_LENGTH;
    }

    /** The sequence restarts for each second / financial year / year depending on the tokens used. */
    public static String periodKey(String pattern, LocalDateTime when) {
        if (pattern != null && pattern.contains("{HHMMSS}")) {
            return YMD.format(when) + HMS.format(when);
        }
        return periodKey(pattern, when.toLocalDate());
    }

    public static String periodKey(String pattern, LocalDate date) {
        if (pattern != null && (pattern.contains("{FY}") || pattern.contains("{FYS}"))) {
            return FinancialYear.shortLabel(date);
        }
        if (pattern != null && (pattern.contains("{YYYY}") || pattern.contains("{YY}") || pattern.contains("{YYYYMMDD}"))) {
            return String.valueOf(date.getYear());
        }
        return "ALL";
    }
}
