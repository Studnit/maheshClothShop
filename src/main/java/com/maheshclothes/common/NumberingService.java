package com.maheshclothes.common;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * Gap-free, race-free document numbers. The upsert takes a row lock that is held until the surrounding
 * transaction commits, so two billers can never receive the same number and a rolled-back sale
 * does not burn a number.
 */
@Service
@RequiredArgsConstructor
public class NumberingService {

    private final JdbcTemplate jdbc;
    private final SettingsService settings;

    public long next(String sequence, String period) {
        Long v = jdbc.queryForObject("""
                insert into number_sequences (seq_name, period_key, last_value) values (?, ?, 1)
                on conflict (seq_name, period_key) do update set last_value = number_sequences.last_value + 1
                returning last_value
                """, Long.class, sequence, period);
        return v == null ? 1 : v;
    }

    /** Sales invoice number using the owner's configured format. */
    public String nextInvoiceNo(LocalDate date) {
        String pattern = settings.get("invoice.format", "{PREFIX}/{FYS}/{SEQ}");
        String period = DocumentNumberFormatter.periodKey(pattern, date);
        long n = next("INVOICE", period);
        return DocumentNumberFormatter.format(pattern, settings.get("invoice.prefix", "INV"), date, n,
                settings.integer("invoice.seq_padding", 5));
    }

    /** Internal documents: PUR/2627/00001 */
    public String nextDocument(String sequence, String prefix, LocalDate date) {
        long n = next(sequence, FinancialYear.shortLabel(date));
        return DocumentNumberFormatter.format("{PREFIX}/{FYS}/{SEQ}", prefix, date, n, 5);
    }

    /** Master-data codes: P00001, S0001, C00001 */
    public String nextCode(String sequence, String prefix, int padding) {
        long n = next(sequence, "ALL");
        return prefix + String.format("%0" + padding + "d", n);
    }
}
