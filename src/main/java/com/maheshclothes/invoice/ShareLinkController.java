package com.maheshclothes.invoice;

import com.maheshclothes.common.SettingsService;
import com.maheshclothes.common.ShopClock;
import com.maheshclothes.common.WebController;
import com.maheshclothes.sales.SalesQueryRepository;
import com.maheshclothes.sales.SalesRepositories.SalesInvoiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Optional;

/**
 * The customer's bill link from WhatsApp: /i/{token} shows the bill with a Download PDF button,
 * /i/{token}/pdf downloads it. The token is unguessable and expires (180 days by default).
 */
@WebController
@RequiredArgsConstructor
public class ShareLinkController {

    private final InvoiceArchiveService archive;
    private final SalesInvoiceRepository invoices;
    private final SalesQueryRepository query;
    private final ShopClock clock;
    private final SettingsService settings;

    @GetMapping("/i/{token}")
    public String page(@PathVariable String token, Model model, jakarta.servlet.http.HttpServletResponse response) {
        Optional<InvoiceArchiveService.Shared> shared = archive.peek(token);
        response.setHeader("X-Robots-Tag", "noindex, nofollow");
        response.setHeader("Cache-Control", "no-store");
        if (shared.isEmpty()) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            model.addAttribute("state", "missing");
            return "public/bill";
        }
        if (shared.get().expired()) {
            response.setStatus(HttpStatus.GONE.value());
            model.addAttribute("state", "expired");
            return "public/bill";
        }
        var inv = invoices.findById(shared.get().invoiceId()).orElseThrow();
        model.addAttribute("state", "ok");
        model.addAttribute("inv", inv);
        model.addAttribute("lines", query.lines(inv.getId()));
        model.addAttribute("payments", query.paymentsOf(inv.getId()));
        model.addAttribute("token", token);
        model.addAttribute("validUntil", shared.get().expiresAt() == null ? null
                : clock.toLocalDate(shared.get().expiresAt()));
        return "public/bill";
    }

    @GetMapping("/i/{token}/pdf")
    @ResponseBody
    public ResponseEntity<byte[]> download(@PathVariable String token, @RequestParam(defaultValue = "false") boolean view) {
        return archive.openByToken(token)
                .map(f -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_PDF)
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                (view ? ContentDisposition.inline() : ContentDisposition.attachment())
                                        .filename(f.fileName()).build().toString())
                        .header("X-Robots-Tag", "noindex, nofollow")
                        .cacheControl(CacheControl.noStore())
                        .body(f.data()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
