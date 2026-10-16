package com.maheshclothes.sales;

import com.maheshclothes.common.BusinessException;
import com.maheshclothes.common.ShopClock;
import com.maheshclothes.common.WebController;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@WebController
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customers;
    private final SalesQueryRepository query;
    private final ShopClock clock;

    @Getter
    @Setter
    public static class CustomerForm {
        private String name;
        private String phone;
        private String address;
        private String notes;
        private BigDecimal creditLimit;
        private boolean active = true;
    }

    @GetMapping("/customers")
    @PreAuthorize("hasAuthority('CUSTOMER_VIEW')")
    public String list(@RequestParam(required = false) String q, @RequestParam(defaultValue = "false") boolean due, Model model) {
        model.addAttribute("rows", query.customers(q, due, 300));
        model.addAttribute("q", q);
        model.addAttribute("due", due);
        model.addAttribute("receivable", query.totalReceivable());
        return "customers/list";
    }

    @GetMapping("/customers/new")
    @PreAuthorize("hasAuthority('CUSTOMER_MANAGE')")
    public String create(Model model) {
        model.addAttribute("form", new CustomerForm());
        model.addAttribute("editing", false);
        return "customers/form";
    }

    @GetMapping("/customers/{id}/edit")
    @PreAuthorize("hasAuthority('CUSTOMER_MANAGE')")
    public String edit(@PathVariable UUID id, Model model) {
        Customer c = customers.get(id);
        CustomerForm f = new CustomerForm();
        f.setName(c.getName());
        f.setPhone(c.getPhone());
        f.setAddress(c.getAddress());
        f.setNotes(c.getNotes());
        f.setCreditLimit(c.getCreditLimit());
        f.setActive(c.isActive());
        model.addAttribute("form", f);
        model.addAttribute("editing", true);
        model.addAttribute("customerId", id);
        return "customers/form";
    }

    @PostMapping({"/customers", "/customers/{id}"})
    @PreAuthorize("hasAuthority('CUSTOMER_MANAGE')")
    public String save(@PathVariable(required = false) UUID id, @ModelAttribute("form") CustomerForm f, Model model,
                       RedirectAttributes redirect) {
        try {
            Customer c = customers.save(id, new CustomerService.CustomerInput(f.getName(), f.getPhone(), f.getAddress(),
                    f.getNotes(), f.getCreditLimit(), f.isActive()));
            redirect.addFlashAttribute("successKey", "customer.saved");
            return "redirect:/customers/" + c.getId();
        } catch (BusinessException e) {
            model.addAttribute("errorKey", e.getKey());
            model.addAttribute("errorArgs", e.getArgs());
            model.addAttribute("editing", id != null);
            model.addAttribute("customerId", id);
            return "customers/form";
        }
    }

    @GetMapping("/customers/{id}")
    @PreAuthorize("hasAuthority('CUSTOMER_VIEW')")
    public String view(@PathVariable UUID id, Model model) {
        Customer c = customers.get(id);
        model.addAttribute("c", c);
        model.addAttribute("summary", query.customer(id));
        Instant from = clock.startOfDay(clock.today().minusYears(3));
        model.addAttribute("invoices", query.invoices(new SalesQueryRepository.InvoiceFilter(from,
                clock.endOfDayExclusive(clock.today()), null, null, id, null, false), 0, 50).items());
        return "customers/view";
    }
}
