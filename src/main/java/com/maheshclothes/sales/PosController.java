package com.maheshclothes.sales;

import com.maheshclothes.catalog.CatalogService;
import com.maheshclothes.common.SettingsService;
import com.maheshclothes.common.WebController;
import com.maheshclothes.salesreturn.ReturnQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import java.util.UUID;

import java.math.BigDecimal;

/** The billing screen. All data operations go through {@link PosApiController}. */
@WebController
@RequiredArgsConstructor
public class PosController {

    private final SalesQueryRepository sales;
    private final SettingsService settings;
    private final ReturnQueryRepository returns;
    private final CatalogService catalog;

    @GetMapping("/pos")
    @PreAuthorize("hasAuthority('SALE_CREATE')")
    public String pos(@RequestParam(required = false) UUID exchange, Model model) {
        model.addAttribute("categories", catalog.activeCategories());
        model.addAttribute("methods", sales.selectableMethods());
        model.addAttribute("taxEnabled", settings.bool("tax.enabled"));
        model.addAttribute("roundOff", settings.bool("invoice.round_off"));
        model.addAttribute("maxDiscountPercent", settings.decimal("discount.cashier_max_percent", new BigDecimal("10")));
        model.addAttribute("waAuto", settings.bool("whatsapp.enabled") && settings.bool("whatsapp.auto_send"));
        model.addAttribute("waDefaultCountry", settings.get("whatsapp.default_country_code", "91"));
        model.addAttribute("creditEnabled", settings.bool("credit.enabled"));
        if (exchange != null) {
            model.addAttribute("exchange", returns.pendingCredit(exchange));
        }
        return "pos";
    }
}
