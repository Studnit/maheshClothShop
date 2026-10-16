package com.maheshclothes.invoice;

import com.maheshclothes.common.SettingsService;
import com.maheshclothes.common.WebController;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** The owner's view of every bill sent (or shared) on WhatsApp, with delivery status and retry. */
@WebController
@RequiredArgsConstructor
public class WhatsappHistoryController {

    private final WhatsappService whatsapp;
    private final SettingsService settings;

    @GetMapping("/whatsapp")
    @PreAuthorize("hasAuthority('WHATSAPP_HISTORY')")
    public String history(@RequestParam(required = false) String status, @RequestParam(required = false) String q,
                          @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("page", whatsapp.history(status, q, Math.max(0, page), 25));
        model.addAttribute("counts", whatsapp.statusCounts());
        model.addAttribute("status", status);
        model.addAttribute("q", q);
        model.addAttribute("apiReady", whatsapp.apiEnabled());
        model.addAttribute("apiConfigured", whatsapp.apiConfigured());
        model.addAttribute("enabledSetting", settings.bool("whatsapp.enabled"));
        return "whatsapp/history";
    }
}
