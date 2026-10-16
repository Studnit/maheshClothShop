package com.maheshclothes.common;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormattingAndNumberingTest {

    @Test
    void indianGrouping() {
        assertEquals("12,34,567.50", IndianFormat.amount(new BigDecimal("1234567.5")));
        assertEquals("1,00,000.00", IndianFormat.amount(new BigDecimal("100000")));
        assertEquals("0.00", IndianFormat.amount(BigDecimal.ZERO));
        assertEquals("1,000.00", IndianFormat.amount(new BigDecimal("999.999")));
        assertEquals("-1,234.50", IndianFormat.amount(new BigDecimal("-1234.5")));
        assertEquals("₹1,23,456.00", IndianFormat.money(new BigDecimal("123456")));
        assertEquals("-₹500.00", IndianFormat.money(new BigDecimal("-500")));
        assertEquals("0.00", IndianFormat.amount(null));
    }

    @Test
    void indianGroupingOfWholeNumbers() {
        assertEquals("999", IndianFormat.number(999));
        assertEquals("1,000", IndianFormat.number(1000));
        assertEquals("12,345", IndianFormat.number(12345));
        assertEquals("1,23,456", IndianFormat.number(123456));
        assertEquals("12,34,567", IndianFormat.number(1234567));
    }

    @Test
    void financialYearRunsAprilToMarch() {
        assertEquals("2026-27", FinancialYear.label(LocalDate.of(2026, 10, 15)));
        assertEquals("2026-27", FinancialYear.label(LocalDate.of(2027, 3, 31)));
        assertEquals("2027-28", FinancialYear.label(LocalDate.of(2027, 4, 1)));
        assertEquals("2627", FinancialYear.shortLabel(LocalDate.of(2026, 10, 15)));
        assertEquals(LocalDate.of(2026, 4, 1), FinancialYear.start(LocalDate.of(2026, 10, 15)));
        assertEquals(LocalDate.of(2027, 3, 31), FinancialYear.end(LocalDate.of(2026, 10, 15)));
    }

    @Test
    void defaultInvoiceNumberFitsTheGstLimit() {
        String no = DocumentNumberFormatter.format("{PREFIX}/{FYS}/{SEQ}", "INV", LocalDate.of(2026, 10, 15), 1, 5);
        assertEquals("INV/2627/00001", no);
        assertFalse(DocumentNumberFormatter.exceedsGstLimit(no));
    }

    @Test
    void longInvoiceFormatIsFlaggedAgainstTheGstLimit() {
        String no = DocumentNumberFormatter.format("{PREFIX}/{FY}/{SEQ}", "INV", LocalDate.of(2026, 10, 15), 1, 5);
        assertEquals("INV/2026-27/00001", no);
        assertTrue(DocumentNumberFormatter.exceedsGstLimit(no));
    }

    @Test
    void formatMustContainTheSequence() {
        assertEquals("settings.error.invoiceformat", assertThrows(BusinessException.class,
                () -> DocumentNumberFormatter.format("{PREFIX}/{FYS}", "INV", LocalDate.now(), 1, 5)).getKey());
    }

    @Test
    void sequenceResetsPerFinancialYearOnlyWhenThePatternHasAYear() {
        LocalDate d = LocalDate.of(2026, 10, 15);
        assertEquals("2627", DocumentNumberFormatter.periodKey("{PREFIX}/{FYS}/{SEQ}", d));
        assertEquals("2026", DocumentNumberFormatter.periodKey("{PREFIX}-{YYYY}-{SEQ}", d));
        assertEquals("ALL", DocumentNumberFormatter.periodKey("{PREFIX}-{SEQ}", d));
    }

    @Test
    void whatsappPhoneNumbers() {
        assertEquals("919876543210", PhoneNumbers.toWhatsApp("98765 43210", "91"));
        assertEquals("919876543210", PhoneNumbers.toWhatsApp("+91 98765-43210", "91"));
        assertEquals("919876543210", PhoneNumbers.toWhatsApp("09876543210", "91"));
        assertEquals("919876543210", PhoneNumbers.toWhatsApp("0091 98765 43210", "91"));
        assertNull(PhoneNumbers.toWhatsApp("5123456789", "91"));   // Indian mobiles start with 6-9
        assertNull(PhoneNumbers.toWhatsApp("12345", "91"));
        assertNull(PhoneNumbers.toWhatsApp(null, "91"));
        assertEquals("91xxxxxxxx10", PhoneNumbers.mask("919876543210"));
    }
}
