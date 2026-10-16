package com.maheshclothes.config;

import com.maheshclothes.common.SettingsService;
import com.maheshclothes.common.WebController;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.Locale;

/** Values every server-rendered page needs (shop name, logo, current path, language). */
@ControllerAdvice(annotations = WebController.class)
@RequiredArgsConstructor
public class GlobalModelAdvice {

    private final SettingsService settings;

    @ModelAttribute("shop")
    public SettingsService.ShopInfo shop() {
        return settings.shop();
    }

    @ModelAttribute("currentUri")
    public String currentUri(HttpServletRequest request) {
        return request.getRequestURI();
    }

    @ModelAttribute("lang")
    public String lang(Locale locale) {
        return "mr".equals(locale.getLanguage()) ? "mr" : "en";
    }

    /** en, mr or both - used by the language switcher. */
    @ModelAttribute("uiLang")
    public String uiLang(Locale locale) {
        return BilingualMessageSource.isBoth(locale) ? "both" : "mr".equals(locale.getLanguage()) ? "mr" : "en";
    }

    @ModelAttribute("creditEnabled")
    public boolean creditEnabled() {
        return settings.bool("credit.enabled");
    }

    @ModelAttribute("whatsappEnabled")
    public boolean whatsappEnabled() {
        return settings.bool("whatsapp.enabled");
    }
}
