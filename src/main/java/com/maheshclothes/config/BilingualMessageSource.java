package com.maheshclothes.config;

import org.springframework.context.support.ResourceBundleMessageSource;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.Objects;

/**
 * Normal English / Marathi text, plus a third "both" language: every label is shown as "मराठी / English".
 * The language "both" is a pseudo-locale selected with ?lang=both (or the shop default in Settings).
 * Keys listed in {@link #SINGLE_LANGUAGE_PREFIXES} (customer WhatsApp texts) are never merged.
 */
public class BilingualMessageSource extends ResourceBundleMessageSource {

    public static final String BOTH = "both";
    public static final Locale BOTH_LOCALE = Locale.of(BOTH);
    private static final Locale MARATHI = Locale.of("mr");
    private static final Locale ENGLISH = Locale.ENGLISH;
    private static final String[] SINGLE_LANGUAGE_PREFIXES = {"wa.message", "wa.customer"};

    public static boolean isBoth(Locale locale) {
        return locale != null && BOTH.equals(locale.getLanguage());
    }

    private static boolean mergeable(String code) {
        for (String p : SINGLE_LANGUAGE_PREFIXES) {
            if (code.startsWith(p)) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected String resolveCodeWithoutArguments(String code, Locale locale) {
        if (!isBoth(locale)) {
            return super.resolveCodeWithoutArguments(code, locale);
        }
        String mr = super.resolveCodeWithoutArguments(code, MARATHI);
        String en = super.resolveCodeWithoutArguments(code, ENGLISH);
        if (mr == null || en == null || Objects.equals(mr, en) || !mergeable(code)) {
            return mergeable(code) ? (mr != null ? mr : en) : en;
        }
        return mr + " / " + en;
    }

    @Override
    protected MessageFormat resolveCode(String code, Locale locale) {
        if (!isBoth(locale)) {
            return super.resolveCode(code, locale);
        }
        MessageFormat mr = super.resolveCode(code, MARATHI);
        MessageFormat en = super.resolveCode(code, ENGLISH);
        if (mr == null || en == null || !mergeable(code)) {
            return mergeable(code) ? (mr != null ? mr : en) : en;
        }
        if (Objects.equals(mr.toPattern(), en.toPattern())) {
            return en;
        }
        return new MessageFormat(mr.toPattern() + " / " + en.toPattern(), ENGLISH);
    }
}
