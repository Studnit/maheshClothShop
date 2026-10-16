package com.maheshclothes.settings;

import com.maheshclothes.common.BusinessException;
import com.maheshclothes.common.SettingsService;
import com.maheshclothes.common.WebController;
import com.maheshclothes.file.FileService;
import com.maheshclothes.invoice.WhatsappService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shop settings. Only keys on these allow-lists can be written from the form, so a crafted request cannot touch
 * anything else. Secrets (WhatsApp token, app secret) are never stored here - they come from environment variables.
 */
@WebController
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SETTINGS_MANAGE')")
public class SettingsController {

    static final List<String> TEXT_KEYS = List.of(
            "shop.name", "shop.address", "shop.village", "shop.district", "shop.state", "shop.pin", "shop.phone",
            "shop.email", "shop.gstin", "tax.default_rate", "invoice.prefix", "invoice.format", "invoice.seq_padding",
            "invoice.footer", "invoice.return_policy", "receipt.paper_width", "language.default", "stock.low_threshold",
            "discount.cashier_max_percent", "return.window_days", "backup.retention_days", "backup.notes",
            "whatsapp.phone_number_id", "whatsapp.template_name", "whatsapp.template_lang_en", "whatsapp.template_lang_mr",
            "whatsapp.link_validity_days", "whatsapp.default_country_code", "app.public_base_url");

    static final List<String> BOOLEAN_KEYS = List.of(
            "tax.enabled", "tax.prices_include_tax", "invoice.round_off", "credit.enabled", "purchase.tax_in_cost",
            "backup.enabled", "whatsapp.enabled", "whatsapp.auto_send");

    private static final Set<String> WIDTHS = Set.of("58", "80");
    private static final String GSTIN = "^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$";

    private final SettingsService settings;
    private final FileService files;
    private final WhatsappService whatsapp;

    @GetMapping("/settings")
    public String form(Model model) {
        model.addAttribute("s", settings.all());
        model.addAttribute("waApiConfigured", whatsapp.apiConfigured());
        return "settings/form";
    }

    @PostMapping("/settings")
    public String save(@RequestParam Map<String, String> form, @RequestParam(required = false) MultipartFile logo,
                       @RequestParam(defaultValue = "false") boolean removeLogo, RedirectAttributes redirect) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String k : TEXT_KEYS) {
            if (form.containsKey(k)) {
                values.put(k, form.get(k) == null ? "" : form.get(k).trim());
            }
        }
        for (String k : BOOLEAN_KEYS) {
            values.put(k, String.valueOf("true".equals(form.get(k)) || "on".equals(form.get(k))));
        }
        validate(values);
        if (logo != null && !logo.isEmpty()) {
            values.put("shop.logo_file_id", files.storeImage(logo).toString());
        } else if (removeLogo) {
            values.put("shop.logo_file_id", "");
        }
        settings.update(values);
        redirect.addFlashAttribute("successKey", "settings.saved");
        return "redirect:/settings";
    }

    private static void validate(Map<String, String> v) {
        String gstin = v.getOrDefault("shop.gstin", "").toUpperCase();
        if (!gstin.isEmpty() && !gstin.matches(GSTIN)) {
            throw new BusinessException("settings.error.gstin");
        }
        v.put("shop.gstin", gstin);
        String name = v.get("shop.name");
        if (name != null && name.isBlank()) {
            throw new BusinessException("settings.error.name");
        }
        String w = v.get("receipt.paper_width");
        if (w != null && !WIDTHS.contains(w)) {
            throw new BusinessException("settings.error.width");
        }
        String lang = v.get("language.default");
        if (lang != null && !lang.equals("en") && !lang.equals("mr") && !lang.equals("both")) {
            throw new BusinessException("settings.error.language");
        }
        for (String k : List.of("tax.default_rate", "discount.cashier_max_percent")) {
            String x = v.get(k);
            if (x != null && !x.isEmpty()) {
                try {
                    BigDecimal d = new BigDecimal(x);
                    if (d.signum() < 0 || d.compareTo(BigDecimal.valueOf(100)) > 0) {
                        throw new BusinessException("settings.error.percent", k);
                    }
                } catch (NumberFormatException e) {
                    throw new BusinessException("settings.error.number", k);
                }
            }
        }
        for (String k : List.of("stock.low_threshold", "return.window_days", "invoice.seq_padding",
                "backup.retention_days", "whatsapp.link_validity_days", "whatsapp.default_country_code")) {
            String x = v.get(k);
            if (x != null && !x.isEmpty() && !x.matches("\\d{1,4}")) {
                throw new BusinessException("settings.error.number", k);
            }
        }
        String base = v.get("app.public_base_url");
        if (base != null && !base.isEmpty()) {
            if (!base.matches("^https?://[^\\s]+$")) {
                throw new BusinessException("settings.error.url");
            }
            v.put("app.public_base_url", base.replaceAll("/+$", ""));
        }
    }
}
