package com.maheshclothes.salesreturn;

import com.maheshclothes.common.BusinessException;
import com.maheshclothes.common.NotFoundException;
import com.maheshclothes.common.SettingsService;
import com.maheshclothes.common.ShopClock;
import com.maheshclothes.common.WebController;
import com.maheshclothes.sales.SalesInvoice;
import com.maheshclothes.sales.SalesQueryRepository;
import com.maheshclothes.sales.SalesRepositories.SalesInvoiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@WebController
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('RETURN_PROCESS')")
public class ReturnController {

    private final ReturnService returns;
    private final ReturnQueryRepository query;
    private final SalesQueryRepository sales;
    private final SalesInvoiceRepository invoices;
    private final ShopClock clock;
    private final SettingsService settings;

    @GetMapping("/returns")
    public String list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page, Model model) {
        LocalDate today = clock.today();
        LocalDate f = from == null ? today.minusDays(60) : from;
        LocalDate t = to == null ? today : to;
        model.addAttribute("page", query.list(clock.startOfDay(f), clock.endOfDayExclusive(t), q, Math.max(0, page), 25));
        model.addAttribute("from", f);
        model.addAttribute("to", t);
        model.addAttribute("q", q);
        return "returns/list";
    }

    /** Step 1: find the invoice by its number; step 2 (invoice chosen): tick what is coming back. */
    @GetMapping("/returns/new")
    public String form(@RequestParam(required = false) UUID invoice, @RequestParam(required = false) String no, Model model) {
        SalesInvoice inv = null;
        if (invoice != null) {
            inv = invoices.findById(invoice).orElseThrow(() -> new NotFoundException("Invoice"));
        } else if (no != null && !no.isBlank()) {
            inv = invoices.findByInvoiceNo(no.trim()).orElse(null);
            if (inv == null) {
                model.addAttribute("errorKey", "return.error.invoice.notfound");
            }
        }
        model.addAttribute("no", no);
        if (inv != null) {
            model.addAttribute("inv", inv);
            model.addAttribute("lines", sales.lines(inv.getId()));
            model.addAttribute("methods", sales.selectableMethods());
            model.addAttribute("requestId", UUID.randomUUID());
            model.addAttribute("windowDays", settings.get("return.window_days", ""));
        }
        return "returns/new";
    }

    @PostMapping("/returns")
    public String process(@RequestParam UUID invoiceId, @RequestParam UUID requestId, @RequestParam String type,
                          @RequestParam String reason, @RequestParam(defaultValue = "CASH") String refundMethod,
                          @RequestParam(required = false) String refundReference, @RequestParam Map<String, String> form,
                          RedirectAttributes redirect) {
        List<ReturnService.ReturnLine> lines = new ArrayList<>();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (e.getKey().startsWith("q_") && e.getValue() != null && !e.getValue().isBlank()) {
                try {
                    int qty = Integer.parseInt(e.getValue().trim());
                    if (qty > 0) {
                        UUID itemId = UUID.fromString(e.getKey().substring(2));
                        boolean damaged = "DAMAGED".equals(form.get("cond_" + itemId));
                        lines.add(new ReturnService.ReturnLine(itemId, qty,
                                damaged ? ReturnService.Condition.DAMAGED : ReturnService.Condition.RESELLABLE));
                    }
                } catch (IllegalArgumentException ex) {
                    throw new BusinessException("return.error.quantity");
                }
            }
        }
        ReturnService.ReturnResult r = returns.process(new ReturnService.ReturnRequest(requestId, invoiceId,
                "EXCHANGE".equals(type), reason, lines, refundMethod, refundReference));
        redirect.addFlashAttribute("successKey", "return.saved");
        return "redirect:/returns/" + r.returnId();
    }

    @GetMapping("/returns/{id}")
    public String view(@PathVariable UUID id, Model model) {
        ReturnQueryRepository.ReturnRow r = query.get(id);
        if (r == null) {
            throw new NotFoundException("Return");
        }
        model.addAttribute("r", r);
        model.addAttribute("items", query.items(id));
        model.addAttribute("methods", sales.selectableMethods());
        return "returns/view";
    }

    @PostMapping("/returns/{id}/refund-credit")
    public String refundCredit(@PathVariable UUID id, @RequestParam(defaultValue = "CASH") String method,
                               @RequestParam(required = false) String reference, RedirectAttributes redirect) {
        returns.refundCredit(id, method, reference);
        redirect.addFlashAttribute("successKey", "return.credit.refunded");
        return "redirect:/returns/" + id;
    }
}
