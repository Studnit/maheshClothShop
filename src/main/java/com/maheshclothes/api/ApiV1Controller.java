package com.maheshclothes.api;

import com.maheshclothes.catalog.ProductQueryRepository;
import com.maheshclothes.common.NotFoundException;
import com.maheshclothes.common.PageResult;
import com.maheshclothes.common.ShopClock;
import com.maheshclothes.inventory.InventoryQueryRepository;
import com.maheshclothes.report.DashboardService;
import com.maheshclothes.report.ReportService;
import com.maheshclothes.sales.SalesQueryRepository;
import com.maheshclothes.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only JSON API for integrations (HTTP Basic, same users and permissions as the web screens).
 * Writes - billing, stock, purchases - go through the web screens, which carry the idempotency and
 * approval rules; keeping the API read-only means an integration can never bypass them.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Shop API", description = "Read-only access to products, stock, bills, customers and reports")
@SecurityRequirement(name = "basicAuth")
public class ApiV1Controller {

    private static final int MAX_PAGE = 100;

    private final ProductQueryRepository products;
    private final InventoryQueryRepository inventory;
    private final SalesQueryRepository sales;
    private final DashboardService dashboard;
    private final ReportService reports;
    private final ShopClock clock;

    private static int size(int s) {
        return Math.max(1, Math.min(MAX_PAGE, s));
    }

    @Operation(summary = "Search products")
    @GetMapping("/products")
    @PreAuthorize("hasAuthority('PRODUCT_VIEW')")
    public PageResult<ProductQueryRepository.ProductRow> products(@RequestParam(required = false) String q,
                                                                  @RequestParam(required = false) UUID category,
                                                                  @RequestParam(defaultValue = "0") int page,
                                                                  @RequestParam(defaultValue = "25") int size) {
        return products.listProducts(new ProductQueryRepository.ProductFilter(q, category, null, null),
                Math.max(0, page), size(size));
    }

    @Operation(summary = "Sizes and colours of one product")
    @GetMapping("/products/{id}/variants")
    @PreAuthorize("hasAuthority('PRODUCT_VIEW')")
    public List<ProductQueryRepository.VariantRow> variants(@PathVariable UUID id) {
        return products.variantsOf(id);
    }

    @Operation(summary = "Find a sellable variant by barcode or SKU")
    @GetMapping("/variants/by-code/{code}")
    @PreAuthorize("hasAuthority('PRODUCT_VIEW')")
    public ProductQueryRepository.VariantHit byCode(@PathVariable String code) {
        return products.findByCode(code).orElseThrow(() -> new NotFoundException("Variant"));
    }

    @Operation(summary = "Current stock; status = low | out | attention")
    @GetMapping("/stock")
    @PreAuthorize("hasAuthority('STOCK_VIEW')")
    public PageResult<InventoryQueryRepository.StockRow> stock(@RequestParam(required = false) String q,
                                                               @RequestParam(required = false) UUID category,
                                                               @RequestParam(required = false) String status,
                                                               @RequestParam(defaultValue = "0") int page,
                                                               @RequestParam(defaultValue = "25") int size) {
        return inventory.stockList(q, category, status, Math.max(0, page), size(size));
    }

    @Operation(summary = "Stock totals and value")
    @GetMapping("/stock/summary")
    @PreAuthorize("hasAuthority('STOCK_VIEW')")
    public InventoryQueryRepository.StockSummary stockSummary() {
        return inventory.summary();
    }

    @Operation(summary = "Bills; cashiers only see their own unless they hold REPORT_SALES")
    @GetMapping("/sales")
    @PreAuthorize("hasAuthority('SALE_VIEW')")
    public PageResult<SalesQueryRepository.InvoiceRow> sales(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String q, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        LocalDate today = clock.today();
        LocalDate f = from == null ? today.minusDays(30) : from;
        LocalDate t = to == null ? today : to;
        String onlyUser = CurrentUser.has("REPORT_SALES") ? null : CurrentUser.username();
        return sales.invoices(new SalesQueryRepository.InvoiceFilter(clock.startOfDay(f), clock.endOfDayExclusive(t), q,
                status, null, onlyUser, false), Math.max(0, page), size(size));
    }

    @Operation(summary = "Customers with outstanding amounts")
    @GetMapping("/customers")
    @PreAuthorize("hasAuthority('CUSTOMER_VIEW')")
    public List<SalesQueryRepository.CustomerRow> customers(@RequestParam(required = false) String q,
                                                            @RequestParam(defaultValue = "false") boolean onlyDue,
                                                            @RequestParam(defaultValue = "50") int limit) {
        return sales.customers(q, onlyDue, size(limit));
    }

    @Operation(summary = "Dashboard figures")
    @GetMapping("/dashboard")
    @PreAuthorize("hasAuthority('DASHBOARD_FINANCIAL')")
    public DashboardService.Data dashboard(@RequestParam(defaultValue = "30") int days) {
        return dashboard.load(Math.max(7, Math.min(90, days)));
    }

    @Operation(summary = "Run any report by key (see /api/v1/reports)")
    @GetMapping("/reports/{key}")
    public ReportService.Result report(@PathVariable String key,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        ReportService.Definition def = reports.find(key).orElseThrow(() -> new NotFoundException("Report"));
        if (!CurrentUser.has(def.permission())) {
            throw new AccessDeniedException("report");
        }
        LocalDate t = to == null ? clock.today() : to;
        return reports.run(key, from == null ? t.withDayOfMonth(1) : from, t);
    }

    @Operation(summary = "Reports the signed-in user may run")
    @GetMapping("/reports")
    public List<Map<String, String>> reportList() {
        return reports.definitions().values().stream().filter(d -> CurrentUser.has(d.permission()))
                .map(d -> Map.of("key", d.key(), "group", d.group())).toList();
    }
}
