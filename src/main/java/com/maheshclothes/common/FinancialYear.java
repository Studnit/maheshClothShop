package com.maheshclothes.common;

import java.time.LocalDate;

/** Indian financial year: 1 April to 31 March. */
public final class FinancialYear {

    private FinancialYear() {
    }

    public static int startYear(LocalDate date) {
        return date.getMonthValue() >= 4 ? date.getYear() : date.getYear() - 1;
    }

    /** "2026-27" */
    public static String label(LocalDate date) {
        int s = startYear(date);
        return s + "-" + String.format("%02d", (s + 1) % 100);
    }

    /** "2627" */
    public static String shortLabel(LocalDate date) {
        int s = startYear(date);
        return String.format("%02d", s % 100) + String.format("%02d", (s + 1) % 100);
    }

    public static LocalDate start(LocalDate date) {
        return LocalDate.of(startYear(date), 4, 1);
    }

    public static LocalDate end(LocalDate date) {
        return LocalDate.of(startYear(date) + 1, 3, 31);
    }
}
