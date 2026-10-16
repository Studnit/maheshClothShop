package com.maheshclothes.report;

import com.maheshclothes.common.MessageHelper;
import com.maheshclothes.common.NotFoundException;
import com.maheshclothes.common.ShopClock;
import com.maheshclothes.common.WebController;
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
import org.springframework.web.bind.annotation.RequestParam;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@WebController
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reports;
    private final ShopClock clock;
    private final MessageHelper msg;

    private ReportService.Definition allowed(String key) {
        ReportService.Definition def = reports.find(key).orElseThrow(() -> new NotFoundException("Report"));
        if (!CurrentUser.has(def.permission())) {
            throw new AccessDeniedException("report");
        }
        return def;
    }

    /** Reports the signed-in user may open, grouped for the index page. */
    @GetMapping("/reports")
    public String index(Model model) {
        Map<String, List<ReportService.Definition>> groups = new LinkedHashMap<>();
        for (ReportService.Definition d : reports.definitions().values()) {
            if (CurrentUser.has(d.permission())) {
                groups.computeIfAbsent(d.group(), g -> new ArrayList<>()).add(d);
            }
        }
        model.addAttribute("groups", groups);
        model.addAttribute("canFinancial", CurrentUser.has("REPORT_FINANCIAL"));
        return "reports/index";
    }

    @GetMapping("/reports/profit-loss")
    @PreAuthorize("hasAuthority('REPORT_FINANCIAL')")
    public String profitLoss(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                             Model model) {
        LocalDate t = to == null ? clock.today() : to;
        LocalDate f = from == null ? t.withDayOfMonth(1) : from;
        model.addAttribute("pl", reports.profitLoss(f, t));
        model.addAttribute("from", f);
        model.addAttribute("to", t);
        return "reports/profit-loss";
    }

    @GetMapping("/reports/{key}")
    public String run(@PathVariable String key,
                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                      Model model) {
        ReportService.Definition def = allowed(key);
        LocalDate t = to == null ? clock.today() : to;
        LocalDate f = from == null ? t.withDayOfMonth(1) : from;
        model.addAttribute("result", reports.run(key, f, t));
        model.addAttribute("def", def);
        model.addAttribute("from", f);
        model.addAttribute("to", t);
        return "reports/run";
    }

    @GetMapping("/reports/{key}/csv")
    public ResponseEntity<byte[]> csv(@PathVariable String key,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        allowed(key);
        LocalDate t = to == null ? clock.today() : to;
        LocalDate f = from == null ? t.withDayOfMonth(1) : from;
        byte[] body = reports.csv(reports.run(key, f, t), c -> msg.get("report.col." + c));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(key + "_" + f + "_" + t + ".csv", StandardCharsets.UTF_8).build().toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }
}
