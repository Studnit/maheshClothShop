package com.maheshclothes.expense;

import com.maheshclothes.common.ShopClock;
import com.maheshclothes.common.WebController;
import com.maheshclothes.file.FileService;
import com.maheshclothes.sales.SalesQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@WebController
@RequiredArgsConstructor
public class ExpenseController {

    private final ExpenseService expenses;
    private final SalesQueryRepository sales;
    private final FileService files;
    private final ShopClock clock;

    @GetMapping("/expenses")
    @PreAuthorize("hasAuthority('EXPENSE_VIEW')")
    public String list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       @RequestParam(required = false) UUID category, @RequestParam(required = false) String q,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        LocalDate today = clock.today();
        LocalDate f = from == null ? today.withDayOfMonth(1) : from;
        LocalDate t = to == null ? today : to;
        model.addAttribute("page", expenses.list(f, t, category, q, Math.max(0, page), 25));
        model.addAttribute("total", expenses.total(f, t));
        model.addAttribute("breakdown", expenses.byCategory(f, t));
        model.addAttribute("categories", expenses.categories(false));
        model.addAttribute("from", f);
        model.addAttribute("to", t);
        model.addAttribute("category", category);
        model.addAttribute("q", q);
        return "expenses/list";
    }

    @GetMapping("/expenses/new")
    @PreAuthorize("hasAuthority('EXPENSE_MANAGE')")
    public String create(Model model) {
        model.addAttribute("e", null);
        form(model, clock.today());
        return "expenses/form";
    }

    @GetMapping("/expenses/{id}/edit")
    @PreAuthorize("hasAuthority('EXPENSE_MANAGE')")
    public String edit(@PathVariable UUID id, Model model) {
        ExpenseService.ExpenseRow e = expenses.get(id);
        model.addAttribute("e", e);
        form(model, e.date());
        return "expenses/form";
    }

    private void form(Model model, LocalDate date) {
        model.addAttribute("categories", expenses.categories(false));
        model.addAttribute("methods", sales.selectableMethods());
        model.addAttribute("date", date);
        model.addAttribute("today", clock.today());
    }

    @PostMapping("/expenses")
    @PreAuthorize("hasAuthority('EXPENSE_MANAGE')")
    public String save(@RequestParam(required = false) UUID id, @RequestParam UUID categoryId,
                       @RequestParam BigDecimal amount,
                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                       @RequestParam String method, @RequestParam(required = false) String description,
                       @RequestParam(required = false) MultipartFile receipt, RedirectAttributes redirect) {
        UUID fileId = receipt != null && !receipt.isEmpty() ? files.storeDocument(receipt) : null;
        expenses.save(id, new ExpenseService.ExpenseInput(categoryId, amount, date, method, description, fileId));
        redirect.addFlashAttribute("successKey", "expense.saved");
        return "redirect:/expenses";
    }

    @PostMapping("/expenses/{id}/delete")
    @PreAuthorize("hasAuthority('EXPENSE_MANAGE')")
    public String delete(@PathVariable UUID id, RedirectAttributes redirect) {
        expenses.delete(id);
        redirect.addFlashAttribute("successKey", "expense.deleted");
        return "redirect:/expenses";
    }

    @PostMapping("/expenses/categories")
    @PreAuthorize("hasAuthority('EXPENSE_MANAGE')")
    public String addCategory(@RequestParam String name, RedirectAttributes redirect) {
        expenses.addCategory(name);
        redirect.addFlashAttribute("successKey", "expense.category.saved");
        return "redirect:/expenses/new";
    }
}
