package com.maheshclothes.config;

import com.maheshclothes.common.SettingsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;

import java.time.Duration;
import java.util.Locale;

/** Remembers the chosen language in a cookie; until a choice is made the owner's default language applies. */
public class AppLocaleResolver extends CookieLocaleResolver {

    private final SettingsService settings;

    public AppLocaleResolver(SettingsService settings) {
        super("MCS_LANG");
        this.settings = settings;
        setCookieMaxAge(Duration.ofDays(365));
        setCookiePath("/");
        setCookieHttpOnly(true);
    }

    @Override
    protected Locale determineDefaultLocale(HttpServletRequest request) {
        String l = settings.defaultLanguage();
        return BilingualMessageSource.BOTH.equals(l) ? BilingualMessageSource.BOTH_LOCALE : Locale.forLanguageTag(l);
    }
}
