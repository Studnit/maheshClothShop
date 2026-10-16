package com.maheshclothes.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PhoneNumbersTest {

    @Test
    void tenDigitIndianMobileGetsCountryCode() {
        assertEquals("919876543210", PhoneNumbers.toWhatsApp("98765 43210", "91"));
        assertEquals("919876543210", PhoneNumbers.toWhatsApp("+91-9876543210", "91"));
        assertEquals("919876543210", PhoneNumbers.toWhatsApp("09876543210", "91"));
        assertEquals("919876543210", PhoneNumbers.toWhatsApp("00919876543210", "91"));
    }

    @Test
    void blankCountryCodeDefaultsToIndia() {
        assertEquals("919876543210", PhoneNumbers.toWhatsApp("9876543210", ""));
        assertEquals("919876543210", PhoneNumbers.toWhatsApp("9876543210", null));
    }

    @Test
    void rejectsNumbersThatCannotBeMobiles() {
        assertNull(PhoneNumbers.toWhatsApp(null, "91"));
        assertNull(PhoneNumbers.toWhatsApp("", "91"));
        assertNull(PhoneNumbers.toWhatsApp("12345", "91"));
        assertNull(PhoneNumbers.toWhatsApp("5876543210", "91"));      // Indian mobiles start with 6-9
        assertNull(PhoneNumbers.toWhatsApp("1234567890123456", "91"));
    }

    @Test
    void masksMiddleDigits() {
        assertEquals("98xxxxxx10", PhoneNumbers.mask("9876543210"));
        assertEquals("123", PhoneNumbers.mask("123"));
    }
}
