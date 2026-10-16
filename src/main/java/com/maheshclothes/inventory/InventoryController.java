package com.maheshclothes.inventory;

import com.maheshclothes.catalog.CatalogService;
import com.maheshclothes.catalog.ProductQueryRepository;
import com.maheshclothes.common.BusinessException;
import com.maheshclothes.common.ShopClock;
import com.maheshclothes.common.WebController;
import com.maheshclothes.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

@WebController
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('STOCK_VIEW')")
public class InventoryController {

    private final InventoryQueryRepository query;
    private final InventoryService inventory;
    private final CatalogService catalog;
    private final ProductQueryRepository products;
    private final ShopClock clock;

    @GetMapping("/inventory")
    public String stock(@RequestParam(required = false) String q, @RequestParam(required = false) UUID category,
                        @RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page,
                        Model model) {
        model.addAttribute("page", query.stockList(q, category, status, Math.max(0, page), 30));
        model.addAttribute("summary", query.summary());
        model.addAttribute("categories", catalog.activeCategories());
        model.addAttribute("q", q);
        model.addAttribute("category", category);
        model.addAttribute("status", status);
        return "inventory/stock";
    }

    @GetMapping("/inventory/insights")
    public String insights(@RequestParam(required = false) UUID product, Model model) {
        model.addAttribute("summary", query.summary());
        model.addAttribute("byCategory", query.valueByCategory());
        model.addAttribute("reorder", query.reorderSuggestions(15));
        model.addAttribute("dead", query.deadStock(60, 15));
        model.addAttribute("fast", query.fastMovers(30, 10));
        List<InventoryQueryRepository.ProductOption> options = query.productOptions();
        model.addAttribute("productOptions", options);
        UUID chosen = product != null ? product : (options.isEmpty() ? null : options.get(0).id());
        model.addAttribute("product", chosen);
        if (chosen != null) {
            // rows = colours, columns = sizes (ordered by their sort order)
            List<InventoryQueryRepository.MatrixCell> cells = query.matrix(chosen);
            TreeMap<Integer, String> sizeCols = new TreeMap<>();
            Map<String, Map<String, InventoryQueryRepository.MatrixCell>> rows = new LinkedHashMap<>();
            cells.stream().sorted(java.util.Comparator.comparingInt(InventoryQueryRepository.MatrixCell::colourOrder)
                            .thenComparing(InventoryQueryRepository.MatrixCell::colour))
                    .forEach(c -> {
                        sizeCols.putIfAbsent(c.sizeOrder() * 1000 + Math.abs(c.size().hashCode() % 1000), c.size());
                        rows.computeIfAbsent(c.colour(), k -> new LinkedHashMap<>()).put(c.size(), c);
                    });
            model.addAttribute("matrixSizes", sizeCols.values().stream().distinct().toList());
            model.addAttribute("matrixRows", rows);
        }
        return "inventory/insights";
    }

    @GetMapping("/inventory/movements")
    public String movements(@RequestParam(required = false) UUID variant, @RequestParam(required = false) String type,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                            @RequestParam(defaultValue = "0") int page, Model model) {
        LocalDate today = clock.today();
        LocalDate f = from == null ? today.minusDays(30) : from;
        LocalDate t = to == null ? today : to;
        model.addAttribute("page", query.movements(variant, type, clock.startOfDay(f), clock.endOfDayExclusive(t),
                Math.max(0, page), 40));
        model.addAttribute("from", f);
        model.addAttribute("to", t);
        model.addAttribute("type", type);
        model.addAttribute("types", MovementType.values());
        return "inventory/movements";
    }

    // ---------------------------------------------------------------- adjustments

    @GetMapping("/inventory/adjust")
    @PreAuthorize("hasAuthority('STOCK_ADJUST')")
    public String adjustForm(@RequestParam(required = false) UUID variant, Model model) {
        model.addAttribute("types", AdjustType.values());
        model.addAttribute("canApprove", CurrentUser.has("STOCK_APPROVE"));
        model.addAttribute("variant", variant == null ? null : products.variantInfo(List.of(variant)).get(variant));
        return "inventory/adjust";
    }

    @PostMapping("/inventory/adjust")
    @PreAuthorize("hasAuthority('STOCK_ADJUST')")
    public String adjust(@RequestParam UUID variantId, @RequestParam AdjustType type, @RequestParam int quantity,
                         @RequestParam(required = false) BigDecimal unitCost, @RequestParam String reason,
                         RedirectAttributes redirect) {
        inventory.adjust(variantId, type, quantity, unitCost, reason);
        redirect.addFlashAttribute("successKey", "stock.adjust.saved");
        return "redirect:/inventory";
    }

    /** Typeahead for the adjustment and label screens. */
    @GetMapping("/inventory/api/variants")
    @ResponseBody
    @PreAuthorize("hasAuthority('STOCK_ADJUST') or hasAuthority('STOCK_VIEW')")
    public List<ProductQueryRepository.VariantHit> variants(@RequestParam(defaultValue = "") String q) {
        return products.searchVariants(q, null, 25, true);
    }

    // ---------------------------------------------------------------- physical count

    @GetMapping("/inventory/count")
    @PreAuthorize("hasAuthority('STOCK_APPROVE')")
    public String countSheet(@RequestParam(required = false) UUID category, Model model) {
        model.addAttribute("page", query.stockList(null, category, null, 0, 500));
        model.addAttribute("categories", catalog.activeCategories());
        model.addAttribute("category", category);
        return "inventory/count";
    }

    @PostMapping("/inventory/count/apply")
    @PreAuthorize("hasAuthority('STOCK_APPROVE')")
    public String applyCount(@RequestParam Map<String, String> form, @RequestParam(defaultValue = "") String note,
                             RedirectAttributes redirect) {
        Map<UUID, Integer> counted = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (e.getKey().startsWith("c_") && e.getValue() != null && !e.getValue().isBlank()) {
                try {
                    int n = Integer.parseInt(e.getValue().trim());
                    if (n < 0) {
                        throw new BusinessException("stock.count.negative");
                    }
                    counted.put(UUID.fromString(e.getKey().substring(2)), n);
                } catch (IllegalArgumentException ex) {
                    throw new BusinessException("stock.count.invalid");
                }
            }
        }
        if (counted.isEmpty()) {
            throw new BusinessException("stock.count.empty");
        }
        inventory.reconcile(counted, note);
        redirect.addFlashAttribute("successKey", "stock.count.done");
        return "redirect:/inventory/movements?type=COUNT";
    }

    // ---------------------------------------------------------------- barcode labels

    @GetMapping("/inventory/labels")
    public String labels(@RequestParam(required = false) UUID product, Model model) {
        model.addAttribute("productOptions", query.productOptions());
        model.addAttribute("product", product);
        model.addAttribute("variants", product == null ? List.of() : products.variantsOf(product));
        model.addAttribute("productName", query.productOptions().stream().filter(o -> o.id().equals(product))
                .findFirst().map(InventoryQueryRepository.ProductOption::name).orElse(""));
        return "inventory/labels";
    }
}
