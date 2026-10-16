package com.maheshclothes.sales;

import com.maheshclothes.common.AuditService;
import com.maheshclothes.common.BusinessException;
import com.maheshclothes.common.ShopClock;
import com.maheshclothes.common.SettingsService;
import com.maheshclothes.common.WebController;
import com.maheshclothes.file.FileService;
import com.maheshclothes.invoice.InvoiceArchiveService;
import com.maheshclothes.invoice.WhatsappService;
import com.maheshclothes.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/** Invoice list, detail, printing, balance collection, cancellation and WhatsApp actions. */
@WebController
@RequiredArgsConstructor
public class SalesController {

    private final SalesService sales;
    private final SalesQueryRepository query;
    private final CustomerService customers;
    private final ShopClock clock;
    private final SettingsService settings;
    private final InvoiceArchiveService archive;
    private final WhatsappService whatsapp;
    private final FileService files;
    private final AuditService audit;

    /** Cashiers see only their own bills; users with REPORT_SALES see everything. */
    private boolean seesAll() {
        return CurrentUser.has("REPORT_SALES");
    }

    private SalesInvoice accessible(UUID id) {
        SalesInvoice inv = sales.get(id);
        if (!seesAll() && !CurrentUser.username().equals(inv.getCreatedBy())) {
            throw new AccessDeniedException("Not your invoice");
        }
        return inv;
    }

    @GetMapping("/sales")
    @PreAuthorize("hasAuthority('SALE_VIEW')")
    public String list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       @RequestParam(required = false) String q, @RequestParam(required = false) String status,
                       @RequestParam(required = false) UUID customer, @RequestParam(required = false) String cashier,
                       @RequestParam(defaultValue = "false") boolean due,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        LocalDate today = clock.today();
        LocalDate f = from == null ? today.minusDays(30) : from;
        LocalDate t = to == null ? today : to;
        String who = seesAll() ? cashier : CurrentUser.username();
        var result = query.invoices(new SalesQueryRepository.InvoiceFilter(clock.startOfDay(f), clock.endOfDayExclusive(t),
                q, status, customer, who, due), Math.max(0, page), 25);
        model.addAttribute("page", result);
        model.addAttribute("from", f);
        model.addAttribute("to", t);
        model.addAttribute("q", q);
        model.addAttribute("status", status);
        model.addAttribute("due", due);
        model.addAttribute("seesAll", seesAll());
        return "sales/list";
    }

    @GetMapping("/sales/{id}")
    @PreAuthorize("hasAuthority('SALE_VIEW')")
    public String view(@PathVariable UUID id, Model model) {
        SalesInvoice inv = accessible(id);
        model.addAttribute("inv", inv);
        model.addAttribute("lines", query.lines(id));
        model.addAttribute("payments", query.paymentsOf(id));
        model.addAttribute("refunds", query.refundsOf(id));
        model.addAttribute("methods", query.selectableMethods());
        model.addAttribute("messages", CurrentUser.has("WHATSAPP_HISTORY") || CurrentUser.has("WHATSAPP_SEND")
                ? whatsapp.historyOf(id) : java.util.List.of());
        model.addAttribute("hasPdf", archive.find(id).isPresent());
        model.addAttribute("waApi", whatsapp.apiEnabled());
        return "sales/view";
    }

    private String printPage(UUID id, String view, Integer width, Model model) {
        SalesInvoice inv = accessible(id);
        model.addAttribute("inv", inv);
        model.addAttribute("lines", query.lines(id));
        model.addAttribute("payments", query.paymentsOf(id));
        model.addAttribute("width", width);
        audit.log("INVOICE_PRINTED", "invoice", id, inv.getInvoiceNo() + " " + view);
        return view;
    }

    @GetMapping("/sales/{id}/print")
    @PreAuthorize("hasAuthority('INVOICE_PRINT')")
    public String print(@PathVariable UUID id, Model model) {
        return printPage(id, "sales/print", null, model);
    }

    /** 58 mm or 80 mm thermal receipt; the shop default is a setting, ?w=58 overrides it. */
    @GetMapping("/sales/{id}/receipt")
    @PreAuthorize("hasAuthority('INVOICE_PRINT')")
    public String receipt(@PathVariable UUID id, @RequestParam(required = false) Integer w, Model model) {
        int width = w != null ? (w == 58 ? 58 : 80) : (settings.integer("receipt.paper_width", 80) == 58 ? 58 : 80);
        return printPage(id, "sales/receipt", width, model);
    }

    @GetMapping("/sales/{id}/pdf")
    @PreAuthorize("hasAuthority('SALE_VIEW')")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id) {
        accessible(id);
        InvoiceArchiveService.Archived doc = archive.ensure(id);
        FileService.StoredFile f = files.load(doc.fileId()).orElseThrow();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(f.fileName()).build().toString())
                .body(f.data());
    }

    @PostMapping("/sales/{id}/payments")
    @PreAuthorize("hasAuthority('SALE_CREATE')")
    public String receive(@PathVariable UUID id, @RequestParam String method, @RequestParam BigDecimal amount,
                          @RequestParam(required = false) String reference, RedirectAttributes redirect) {
        accessible(id);
        sales.receiveBalance(id, method, amount, reference);
        redirect.addFlashAttribute("successKey", "sale.payment.saved");
        return "redirect:/sales/" + id;
    }

    @PostMapping("/sales/{id}/cancel")
    @PreAuthorize("hasAuthority('SALE_CANCEL')")
    public String cancel(@PathVariable UUID id, @RequestParam String reason,
                         @RequestParam(defaultValue = "CASH") String refundMethod, RedirectAttributes redirect) {
        sales.cancel(id, reason, refundMethod);
        redirect.addFlashAttribute("successKey", "sale.cancelled");
        return "redirect:/sales/" + id;
    }

    // ---------------------------------------------------------------- WhatsApp

    /** The message about to be sent, for the preview shown right after billing. */
    @GetMapping("/sales/{id}/whatsapp/preview")
    @PreAuthorize("hasAuthority('WHATSAPP_SEND')")
    @ResponseBody
    public WhatsappService.Preview waPreview(@PathVariable UUID id) {
        accessible(id);
        return whatsapp.preview(id);
    }

    /** Called when the owner presses Send in the preview, so the history shows what was sent. */
    @PostMapping("/sales/{id}/whatsapp/logged")
    @PreAuthorize("hasAuthority('WHATSAPP_SEND')")
    @ResponseBody
    public Map<String, Object> waLogged(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        accessible(id);
        whatsapp.logShare(id, body.get("text"));
        return Map.of("ok", true);
    }

    /** One tap: open WhatsApp with the thank-you message and a download link for the bill. */
    @GetMapping("/sales/{id}/whatsapp")
    @PreAuthorize("hasAuthority('WHATSAPP_SEND')")
    public String shareLink(@PathVariable UUID id, @RequestParam(required = false) String lang) {
        accessible(id);
        // without a customer number WhatsApp simply opens its contact picker with the message filled in
        return "redirect:" + whatsapp.prepareShare(id, "mr".equals(lang) ? "mr" : "en".equals(lang) ? "en" : null).url();
    }

    /** Send the PDF from the owner's WhatsApp Business account (Cloud API). */
    @PostMapping("/sales/{id}/whatsapp/api")
    @PreAuthorize("hasAuthority('WHATSAPP_SEND')")
    public String sendApi(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean consent,
                          RedirectAttributes redirect) {
        accessible(id);
        if (!consent) {
            throw new BusinessException("wa.error.consent");
        }
        if (!whatsapp.apiEnabled()) {
            throw new BusinessException("wa.error.notconfigured");
        }
        whatsapp.sendByApi(id, true);
        redirect.addFlashAttribute("successKey", "wa.queued");
        return "redirect:/sales/" + id;
    }

    @PostMapping("/whatsapp/{messageId}/resend")
    @PreAuthorize("hasAuthority('WHATSAPP_HISTORY')")
    public String resend(@PathVariable UUID messageId, @RequestParam(required = false) String back,
                         RedirectAttributes redirect) {
        if (!whatsapp.apiEnabled()) {
            throw new BusinessException("wa.error.notconfigured");
        }
        whatsapp.resend(messageId);
        redirect.addFlashAttribute("successKey", "wa.queued");
        return "redirect:" + (back != null && back.startsWith("/") && !back.startsWith("//") ? back : "/whatsapp");
    }
}
