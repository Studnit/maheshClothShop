package com.maheshclothes.purchasing;

import com.maheshclothes.common.BusinessException;
import com.maheshclothes.common.ShopClock;
import com.maheshclothes.common.WebController;
import com.maheshclothes.sales.SalesQueryRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@WebController
@RequiredArgsConstructor
public class SupplierController {

    private final SupplierService suppliers;
    private final PurchaseQueryRepository query;
    private final PurchaseService purchases;
    private final SalesQueryRepository sales;
    private final ShopClock clock;

    @Getter
    @Setter
    public static class SupplierForm {
        private String name;
        private String phone;
        private String address;
        private String gstin;
        private BigDecimal openingBalance = BigDecimal.ZERO;
        private String notes;
        private boolean active = true;
    }

    @GetMapping("/suppliers")
    @PreAuthorize("hasAuthority('SUPPLIER_VIEW')")
    public String list(@RequestParam(required = false) String q, @RequestParam(defaultValue = "false") boolean due, Model model) {
        model.addAttribute("rows", query.suppliers(q, due));
        model.addAttribute("q", q);
        model.addAttribute("due", due);
        model.addAttribute("payable", query.totalPayable());
        return "suppliers/list";
    }

    @GetMapping("/suppliers/new")
    @PreAuthorize("hasAuthority('SUPPLIER_MANAGE')")
    public String create(Model model) {
        model.addAttribute("form", new SupplierForm());
        model.addAttribute("editing", false);
        return "suppliers/form";
    }

    @GetMapping("/suppliers/{id}/edit")
    @PreAuthorize("hasAuthority('SUPPLIER_MANAGE')")
    public String edit(@PathVariable UUID id, Model model) {
        Supplier s = suppliers.get(id);
        SupplierForm f = new SupplierForm();
        f.setName(s.getName());
        f.setPhone(s.getPhone());
        f.setAddress(s.getAddress());
        f.setGstin(s.getGstin());
        f.setOpeningBalance(s.getOpeningBalance());
        f.setNotes(s.getNotes());
        f.setActive(s.isActive());
        model.addAttribute("form", f);
        model.addAttribute("editing", true);
        model.addAttribute("supplierId", id);
        return "suppliers/form";
    }

    @PostMapping({"/suppliers", "/suppliers/{id}"})
    @PreAuthorize("hasAuthority('SUPPLIER_MANAGE')")
    public String save(@PathVariable(required = false) UUID id, @ModelAttribute("form") SupplierForm f, Model model,
                       RedirectAttributes redirect) {
        try {
            Supplier s = suppliers.save(id, new SupplierService.SupplierInput(f.getName(), f.getPhone(), f.getAddress(),
                    f.getGstin(), f.getOpeningBalance(), f.getNotes(), f.isActive()));
            redirect.addFlashAttribute("successKey", "supplier.saved");
            return "redirect:/suppliers/" + s.getId();
        } catch (BusinessException e) {
            model.addAttribute("errorKey", e.getKey());
            model.addAttribute("errorArgs", e.getArgs());
            model.addAttribute("editing", id != null);
            model.addAttribute("supplierId", id);
            return "suppliers/form";
        }
    }

    @GetMapping("/suppliers/{id}")
    @PreAuthorize("hasAuthority('SUPPLIER_VIEW')")
    public String view(@PathVariable UUID id, Model model) {
        model.addAttribute("s", suppliers.get(id));
        model.addAttribute("summary", query.supplier(id));
        model.addAttribute("ledger", query.ledger(id));
        model.addAttribute("payments", query.paymentsOf(id, null));
        model.addAttribute("methods", sales.selectableMethods());
        model.addAttribute("today", clock.today());
        return "suppliers/view";
    }

    @PostMapping("/suppliers/{id}/pay")
    @PreAuthorize("hasAuthority('SUPPLIER_PAY')")
    public String pay(@PathVariable UUID id, @RequestParam BigDecimal amount, @RequestParam String method,
                      @RequestParam(required = false) String reference, @RequestParam(required = false) String notes,
                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate paidOn,
                      RedirectAttributes redirect) {
        purchases.pay(id, null, amount, method, reference, paidOn == null ? clock.today() : paidOn, notes);
        redirect.addFlashAttribute("successKey", "supplier.payment.saved");
        return "redirect:/suppliers/" + id;
    }
}
