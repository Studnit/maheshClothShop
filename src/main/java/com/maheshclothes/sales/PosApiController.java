package com.maheshclothes.sales;

import com.maheshclothes.catalog.ProductQueryRepository;
import com.maheshclothes.catalog.ProductQueryRepository.VariantHit;
import com.maheshclothes.common.PhoneNumbers;
import com.maheshclothes.common.SettingsService;
import com.maheshclothes.security.CurrentUser;
import com.maheshclothes.invoice.WhatsappService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** JSON endpoints used by the billing screen. Errors come back as RFC 9457 problem details in the user's language. */
@RestController
@RequestMapping("/pos/api")
@PreAuthorize("hasAuthority('SALE_CREATE')")
@RequiredArgsConstructor
public class PosApiController {

    private final ProductQueryRepository products;
    private final SalesQueryRepository sales;
    private final CustomerService customers;
    private final SalesService salesService;
    private final WhatsappService whatsapp;
    private final SettingsService settings;

    @GetMapping("/search")
    public List<VariantHit> search(@RequestParam(defaultValue = "") String q,
                                   @RequestParam(required = false) UUID category) {
        return products.searchVariants(q, category, 120, false);
    }

    @GetMapping("/code")
    public ResponseEntity<VariantHit> code(@RequestParam String code) {
        return products.findByCode(code.trim()).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/customers")
    @PreAuthorize("hasAuthority('CUSTOMER_VIEW')")
    public List<Map<String, Object>> customers(@RequestParam(defaultValue = "") String q) {
        return sales.customers(q, false, 8).stream().filter(SalesQueryRepository.CustomerRow::active).map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id());
            m.put("name", c.name());
            m.put("phone", c.phone());
            m.put("due", c.outstanding());
            m.put("creditLimit", c.creditLimit());
            return m;
        }).toList();
    }

    public record QuickCustomer(String name, String phone) {
    }

    @PostMapping("/customers")
    @PreAuthorize("hasAuthority('CUSTOMER_MANAGE')")
    public Map<String, Object> addCustomer(@RequestBody QuickCustomer in) {
        Customer c = customers.quickAdd(in.name(), in.phone());
        return Map.of("id", c.getId(), "name", c.getName(), "phone", c.getPhone() == null ? "" : c.getPhone(),
                "due", customers.outstanding(c.getId()));
    }

    public record CheckoutLine(UUID variantId, int quantity, BigDecimal discount) {
    }

    public record CheckoutPayment(String method, BigDecimal amount, String reference) {
    }

    public record Checkout(UUID clientRequestId, UUID customerId, String walkInName, String walkInPhone,
                           List<CheckoutLine> lines, BigDecimal invoiceDiscount, List<CheckoutPayment> payments,
                           String notes, boolean sendWhatsApp, UUID exchangeReturnId, BigDecimal exchangeCredit) {
    }

    public record QuoteRequest(List<CheckoutLine> lines, BigDecimal invoiceDiscount) {
    }

    /** Server-side price for the cart on screen, so the cashier always sees what the invoice will say. */
    @PostMapping("/quote")
    public Map<String, Object> quote(@RequestBody QuoteRequest q) {
        PricingCalculator.Result r = salesService.quote(
                q.lines().stream().map(l -> new SalesService.CartLine(l.variantId(), l.quantity(), l.discount())).toList(),
                q.invoiceDiscount());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("subtotal", r.subtotal());
        out.put("lineDiscount", r.lineDiscountTotal());
        out.put("invoiceDiscount", r.invoiceDiscount());
        out.put("tax", r.taxTotal());
        out.put("roundOff", r.roundOff());
        out.put("total", r.grandTotal());
        out.put("lineTotals", r.lines().stream().map(PricingCalculator.LineResult::lineTotal).toList());
        return out;
    }

    @PostMapping("/checkout")
    public Map<String, Object> checkout(@RequestBody Checkout c) {
        SalesInvoice inv = salesService.createSale(new SalesService.SaleRequest(c.clientRequestId(), c.customerId(),
                c.walkInName(), c.walkInPhone(),
                c.lines().stream().map(l -> new SalesService.CartLine(l.variantId(), l.quantity(), l.discount())).toList(),
                c.invoiceDiscount(),
                c.payments() == null ? List.of() : c.payments().stream()
                        .map(p -> new SalesService.PaymentInput(p.method(), p.amount(), p.reference())).toList(),
                c.exchangeReturnId(), c.exchangeReturnId() == null ? null : c.exchangeCredit(), c.notes(), c.sendWhatsApp()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("invoiceId", inv.getId());
        out.put("invoiceNo", inv.getInvoiceNo());
        out.put("total", inv.getTotal());
        out.put("paid", inv.getAmountPaid());
        out.put("balance", inv.getBalanceDue());
        out.put("phoneValid", PhoneNumbers.toWhatsApp(inv.getCustomerPhone(),
                settings.get("whatsapp.default_country_code", "91")) != null);
        out.put("autoSend", c.sendWhatsApp() && whatsapp.apiEnabled() && settings.bool("whatsapp.auto_send"));
        out.put("canShare", CurrentUser.has("WHATSAPP_SEND"));
        return out;
    }
}
