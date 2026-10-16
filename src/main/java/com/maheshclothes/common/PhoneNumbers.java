package com.maheshclothes.common;

/** Phone number clean-up for WhatsApp (international format, digits only, no "+"). */
public final class PhoneNumbers {

    private PhoneNumbers() {
    }

    /**
     * Returns the number in WhatsApp format (e.g. "919876543210") or null when it cannot be a valid mobile number.
     */
    public static String toWhatsApp(String raw, String defaultCountryCode) {
        if (raw == null) {
            return null;
        }
        String cc = defaultCountryCode == null || defaultCountryCode.isBlank() ? "91" : defaultCountryCode.trim();
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.startsWith("00")) {
            digits = digits.substring(2);
        }
        if (digits.length() == 11 && digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        if (digits.length() == 10) {
            digits = cc + digits;
        }
        if (digits.length() < 8 || digits.length() > 15) {
            return null;
        }
        if ("91".equals(cc) && digits.startsWith("91") && digits.length() == 12) {
            char first = digits.charAt(2);
            if (first < '6' || first > '9') {
                return null;   // Indian mobile numbers start with 6-9
            }
        }
        return digits;
    }

    /** Mask for lists: 98xxxxxx10 */
    public static String mask(String phone) {
        if (phone == null || phone.length() < 6) {
            return phone;
        }
        return phone.substring(0, 2) + "x".repeat(phone.length() - 4) + phone.substring(phone.length() - 2);
    }
}
