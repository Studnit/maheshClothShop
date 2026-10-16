package com.maheshclothes.purchasing;

import com.maheshclothes.catalog.ProductQueryRepository;
import com.maheshclothes.common.BusinessException;
import com.maheshclothes.common.ShopClock;
import com.maheshclothes.common.SettingsService;
import com.maheshclothes.common.WebController;
import com.maheshclothes.file.FileService;
import com.maheshclothes.purchasing.PurchaseQueryRepository.ItemRow;
import com.maheshclothes.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@WebController
@RequiredArgsConstructor
public class PurchaseController {

    private final PurchaseService purchases;
    private final PurchaseQueryRepository query;
    private final SupplierService suppliers;
    private final ProductQueryRepository products;
    private final FileService files;
    private final ShopClock clock;
    private final SettingsService settings;
    private final com.maheshclothes.sales.SalesQueryRepository sales;

    @GetMapping("/purchases")
    @PreAuthorize("hasAuthority('PURCHASE_VIEW')")
    public String list(@RequestParam(required = false) UUID supplier, @RequestParam(required = false) String status,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page, Model model) {
        LocalDate today = clock.today();
        LocalDate f = from == null ? today.minusDays(90) : from;
        LocalDate t = to == null ? today : to;
        model.addAttribute("page", query.purchases(supplier, status, f, t, q, Math.max(0, page), 25));
        model.addAttribute("suppliers", query.suppliers(null, false));
        model.addAttribute("supplier", supplier);
        model.addAttribute("status", status);
        model.addAttribute("from", f);
        model.addAttribute("to", t);
        model.addAttribute("q", q);
        return "purchases/list";
    }

    @GetMapping("/purchases/new")
    @PreAuthorize("hasAuthority('PURCHASE_MANAGE')")
    public String create(@RequestParam(required = false) UUID supplier, Model model) {
        model.addAttribute("suppliers", query.suppliers(null, false).stream().filter(s -> s.active()).toList());
        model.addAttribute("purchaseId", null);
        model.addAttribute("supplierId", supplier);
        model.addAttribute("date", clock.today());
        model.addAttribute("invNo", "");
        model.addAttribute("extra", BigDecimal.ZERO);
        model.addAttribute("notes", "");
        model.addAttribute("linesJson", "[]");
        model.addAttribute("taxOn", settings.bool("tax.enabled"));
        return "purchases/form";
    }

    @GetMapping("/purchases/{id}/edit")
    @PreAuthorize("hasAuthority('PURCHASE_MANAGE')")
    public String edit(@PathVariable UUID id, Model model) throws Exception {
        PurchaseInvoice inv = purchases.get(id);
        if (inv.getStatus() != PurchaseStatus.DRAFT) {
            throw new BusinessException("purchase.error.notdraft");
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        for (ItemRow r : query.items(id)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("variantId", r.variantId());
            m.put("label", r.productName() + " · " + (r.colourName() == null ? "" : r.colourName()) + (r.sizeName() == null ? "" : " / " + r.sizeName()));
            m.put("sku", r.sku());
            m.put("quantity", r.quantity());
            m.put("unitPrice", r.unitPrice());
            m.put("discount", r.discount());
            m.put("taxRate", r.taxRate());
            lines.add(m);
        }
        model.addAttribute("suppliers", query.suppliers(null, false));
        model.addAttribute("purchaseId", id);
        model.addAttribute("supplierId", inv.getSupplierId());
        model.addAttribute("date", inv.getPurchaseDate());
        model.addAttribute("invNo", inv.getSupplierInvoiceNo());
        model.addAttribute("extra", inv.getAdditionalCosts());
        model.addAttribute("notes", inv.getNotes() == null ? "" : inv.getNotes());
        model.addAttribute("linesJson", new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(lines));
        model.addAttribute("taxOn", settings.bool("tax.enabled"));
        return "purchases/form";
    }

    // ---------------------------------------------------------------- JSON for the form

    public record LineIn(UUID variantId, int quantity, BigDecimal unitPrice, BigDecimal discount, BigDecimal taxRate) {
    }

    public record SaveIn(UUID id, UUID supplierId, String supplierInvoiceNo, LocalDate purchaseDate, List<LineIn> lines,
                         BigDecimal additionalCosts, String notes, boolean receiveNow) {
    }

    @PostMapping(value = "/purchases/api/quote", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    @PreAuthorize("hasAuthority('PURCHASE_MANAGE')")
    public Map<String, Object> quote(@RequestBody SaveIn in) {
        PurchaseCalculator.Result r = PurchaseCalculator.compute(in.lines().stream()
                .map(l -> new PurchaseCalculator.LineInput(l.quantity(), l.unitPrice(), l.discount(), l.taxRate())).toList(),
                in.additionalCosts(), settings.bool("purchase.tax_in_cost"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("subtotal", r.subtotal());
        out.put("discount", r.discountTotal());
        out.put("tax", r.taxTotal());
        out.put("extra", r.additionalCosts());
        out.put("total", r.total());
        out.put("landed", r.lines().stream().map(PurchaseCalculator.LineResult::landedUnitCost).toList());
        out.put("lineTotals", r.lines().stream().map(PurchaseCalculator.LineResult::lineTotal).toList());
        return out;
    }

    @PostMapping(value = "/purchases/api/save", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    @PreAuthorize("hasAuthority('PURCHASE_MANAGE')")
    public Map<String, Object> save(@RequestBody SaveIn in) {
        PurchaseInvoice inv = purchases.save(new PurchaseService.PurchaseRequest(in.id(), in.supplierId(),
                in.supplierInvoiceNo(), in.purchaseDate(),
                in.lines().stream().map(l -> new PurchaseService.PurchaseLine(l.variantId(), l.quantity(), l.unitPrice(),
                        l.discount(), l.taxRate())).toList(),
                in.additionalCosts(), in.notes(), null, in.receiveNow()));
        return Map.of("id", inv.getId(), "status", inv.getStatus().name(), "purchaseNo", inv.getPurchaseNo());
    }

    // ---------------------------------------------------------------- detail & actions

    @GetMapping("/purchases/{id}")
    @PreAuthorize("hasAuthority('PURCHASE_VIEW')")
    public String view(@PathVariable UUID id, Model model) {
        PurchaseInvoice inv = purchases.get(id);
        model.addAttribute("inv", inv);
        model.addAttribute("supplier", suppliers.get(inv.getSupplierId()));
        model.addAttribute("items", query.items(id));
        model.addAttribute("payments", query.paymentsOf(inv.getSupplierId(), id));
        model.addAttribute("returns", query.returnsOf(id));
        model.addAttribute("today", clock.today());
        model.addAttribute("canPay", CurrentUser.has("SUPPLIER_PAY"));
        model.addAttribute("methods", sales.selectableMethods());
        return "purchases/view";
    }

    @PostMapping("/purchases/{id}/receive")
    @PreAuthorize("hasAuthority('PURCHASE_MANAGE')")
    public String receive(@PathVariable UUID id, RedirectAttributes redirect) {
        purchases.receive(id);
        redirect.addFlashAttribute("successKey", "purchase.received");
        return "redirect:/purchases/" + id;
    }

    @PostMapping("/purchases/{id}/cancel")
    @PreAuthorize("hasAuthority('PURCHASE_MANAGE')")
    public String cancel(@PathVariable UUID id, RedirectAttributes redirect) {
        purchases.cancelDraft(id);
        redirect.addFlashAttribute("successKey", "purchase.cancelled");
        return "redirect:/purchases";
    }

    @PostMapping("/purchases/{id}/pay")
    @PreAuthorize("hasAuthority('SUPPLIER_PAY')")
    public String pay(@PathVariable UUID id, @RequestParam BigDecimal amount, @RequestParam String method,
                      @RequestParam(required = false) String reference,
                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate paidOn,
                      RedirectAttributes redirect) {
        PurchaseInvoice inv = purchases.get(id);
        purchases.pay(inv.getSupplierId(), id, amount, method, reference, paidOn == null ? clock.today() : paidOn, null);
        redirect.addFlashAttribute("successKey", "supplier.payment.saved");
        return "redirect:/purchases/" + id;
    }

    @PostMapping("/purchases/{id}/return")
    @PreAuthorize("hasAuthority('PURCHASE_MANAGE')")
    public String returnGoods(@PathVariable UUID id, @RequestParam Map<String, String> form,
                              @RequestParam String reason, RedirectAttributes redirect) {
        Map<UUID, Integer> qty = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (e.getKey().startsWith("q_") && e.getValue() != null && !e.getValue().isBlank()) {
                try {
                    int n = Integer.parseInt(e.getValue().trim());
                    if (n > 0) {
                        qty.put(UUID.fromString(e.getKey().substring(2)), n);
                    }
                } catch (IllegalArgumentException ex) {
                    throw new BusinessException("return.error.quantity");
                }
            }
        }
        purchases.returnToSupplier(id, qty, reason, clock.today());
        redirect.addFlashAttribute("successKey", "supplier.return.saved");
        return "redirect:/purchases/" + id;
    }

    @PostMapping("/purchases/{id}/attachment")
    @PreAuthorize("hasAuthority('PURCHASE_MANAGE')")
    public String attach(@PathVariable UUID id, @RequestParam MultipartFile file, RedirectAttributes redirect) {
        UUID fileId = files.storeDocument(file);
        if (fileId == null) {
            throw new BusinessException("file.error.empty");
        }
        purchases.attach(id, fileId);
        redirect.addFlashAttribute("successKey", "common.saved");
        return "redirect:/purchases/" + id;
    }
}
