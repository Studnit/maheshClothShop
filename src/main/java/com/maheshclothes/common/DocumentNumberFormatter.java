package com.maheshclothes.common;

import java.time.LocalDate;

/**
 * Builds document numbers from an owner-configurable pattern.
 * Tokens: {PREFIX} {FY} (2026-27) {FYS} (2627) {YYYY} {YY} {SEQ}.
 */
public final class DocumentNumberFormatter {

    /** GST rules limit an invoice number to 16 characters (letters, digits, "/" and "-"). */
    public static final int GST_MAX_LENGTH = 16;

    private DocumentNumberFormatter() {
    }

    public static String format(String pattern, String prefix, LocalDate date, long seq, int padding) {
        if (pattern == null || !pattern.contains("{SEQ}")) {
            throw new BusinessException("settings.error.invoiceformat");
        }
        int pad = Math.max(1, Math.min(10, padding));
        String seqText = String.format("%0" + pad + "d", seq);
        return pattern
                .replace("{PREFIX}", prefix == null ? "" : prefix)
                .replace("{FYS}", FinancialYear.shortLabel(date))
                .replace("{FY}", FinancialYear.label(date))
                .replace("{YYYY}", String.valueOf(date.getYear()))
                .replace("{YY}", String.format("%02d", date.getYear() % 100))
                .replace("{SEQ}", seqText);
    }

    public static boolean exceedsGstLimit(String formatted) {
        return formatted != null && formatted.length() > GST_MAX_LENGTH;
    }

    /** The sequence resets for each financial year if the pattern contains an FY token, otherwise it never resets. */
    public static String periodKey(String pattern, LocalDate date) {
        if (pattern != null && (pattern.contains("{FY}") || pattern.contains("{FYS}"))) {
            return FinancialYear.shortLabel(date);
        }
        if (pattern != null && (pattern.contains("{YYYY}") || pattern.contains("{YY}"))) {
            return String.valueOf(date.getYear());
        }
        return "ALL";
    }
}
