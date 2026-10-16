package com.maheshclothes.catalog;

import com.maheshclothes.common.WebController;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

/** Categories, brands, sizes and colours: one screen, four lists. */
@WebController
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('PRODUCT_MANAGE')")
public class CatalogController {

    private final CatalogService catalog;

    @GetMapping("/catalog")
    public String index(@RequestParam(defaultValue = "categories") String type, Model model) {
        CatalogService.Type t = CatalogService.Type.of(type);
        model.addAttribute("type", t.name());
        model.addAttribute("items", catalog.list(t));
        model.addAttribute("types", CatalogService.Type.values());
        return "catalog/index";
    }

    @PostMapping("/catalog/{type}")
    public String save(@PathVariable String type, @RequestParam(required = false) UUID id, @RequestParam String name,
                       @RequestParam(defaultValue = "0") int sortOrder, @RequestParam(required = false) String hex,
                       RedirectAttributes redirect) {
        catalog.save(CatalogService.Type.of(type), id, name, sortOrder, hex);
        redirect.addFlashAttribute("successKey", "catalog.saved");
        return "redirect:/catalog?type=" + type;
    }

    @PostMapping("/catalog/{type}/{id}/toggle")
    public String toggle(@PathVariable String type, @PathVariable UUID id, RedirectAttributes redirect) {
        catalog.toggle(CatalogService.Type.of(type), id);
        redirect.addFlashAttribute("successKey", "catalog.saved");
        return "redirect:/catalog?type=" + type;
    }
}
