package com.maheshclothes.catalog;

import com.maheshclothes.catalog.ProductQueryRepository.ProductFilter;
import com.maheshclothes.common.BusinessException;
import com.maheshclothes.common.WebController;
import com.maheshclothes.file.FileService;
import com.maheshclothes.security.CurrentUser;
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
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@WebController
@RequiredArgsConstructor
public class ProductController {

    private final ProductService products;
    private final ProductQueryRepository query;
    private final CatalogService catalog;
    private final FileService files;

    @Getter
    @Setter
    public static class ProductForm {
        private String name;
        private String sku;
        private UUID categoryId;
        private UUID brandId;
        private String description;
        private String fabric;
        private String design;
        private BigDecimal purchaseCost;
        private BigDecimal sellingPrice;
        private BigDecimal mrp;
        private BigDecimal taxRate;
        private String hsnCode;
        private Integer minStock;
        private String barcode;
        private boolean active = true;
        private List<UUID> sizeIds = new ArrayList<>();
        private List<UUID> colourIds = new ArrayList<>();

        ProductService.ProductInput toInput(BigDecimal costFallback) {
            return new ProductService.ProductInput(name, sku, categoryId, brandId, description, fabric, design,
                    CurrentUser.has("COST_VIEW") ? purchaseCost : costFallback, sellingPrice, mrp, taxRate, hsnCode,
                    minStock, barcode, active);
        }
    }

    private void lists(Model model) {
        model.addAttribute("categories", catalog.activeCategories());
        model.addAttribute("brands", catalog.activeBrands());
        model.addAttribute("sizes", catalog.activeSizes());
        model.addAttribute("colours", catalog.activeColours());
    }

    @GetMapping("/products")
    @PreAuthorize("hasAuthority('PRODUCT_VIEW')")
    public String list(@RequestParam(required = false) String q, @RequestParam(required = false) UUID category,
                       @RequestParam(required = false) UUID brand, @RequestParam(defaultValue = "active") String status,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("page", query.listProducts(new ProductFilter(q, category, brand, status), Math.max(0, page), 24));
        model.addAttribute("q", q);
        model.addAttribute("category", category);
        model.addAttribute("brand", brand);
        model.addAttribute("status", status);
        lists(model);
        return "products/list";
    }

    @GetMapping("/products/new")
    @PreAuthorize("hasAuthority('PRODUCT_MANAGE')")
    public String create(Model model) {
        model.addAttribute("form", new ProductForm());
        model.addAttribute("editing", false);
        lists(model);
        return "products/form";
    }

    @PostMapping("/products")
    @PreAuthorize("hasAuthority('PRODUCT_MANAGE')")
    public String save(@ModelAttribute("form") ProductForm form, @RequestParam(required = false) MultipartFile image,
                       Model model, RedirectAttributes redirect) {
        try {
            Product p = products.create(form.toInput(BigDecimal.ZERO), form.getSizeIds(), form.getColourIds());
            if (image != null && !image.isEmpty()) {
                products.setImage(p.getId(), files.storeImage(image));
            }
            redirect.addFlashAttribute("successKey", "product.saved");
            return "redirect:/products/" + p.getId();
        } catch (BusinessException e) {
            model.addAttribute("errorKey", e.getKey());
            model.addAttribute("errorArgs", e.getArgs());
            model.addAttribute("editing", false);
            lists(model);
            return "products/form";
        }
    }

    @GetMapping("/products/{id}")
    @PreAuthorize("hasAuthority('PRODUCT_VIEW')")
    public String view(@PathVariable UUID id, Model model) {
        model.addAttribute("p", products.get(id));
        model.addAttribute("variants", query.variantsOf(id));
        model.addAttribute("category", catalog.activeCategories().stream()
                .filter(c -> c.getId().equals(products.get(id).getCategoryId())).findFirst().map(Category::getName).orElse("-"));
        lists(model);
        return "products/view";
    }

    @GetMapping("/products/{id}/edit")
    @PreAuthorize("hasAuthority('PRODUCT_MANAGE')")
    public String edit(@PathVariable UUID id, Model model) {
        Product p = products.get(id);
        ProductForm f = new ProductForm();
        f.setName(p.getName());
        f.setSku(p.getSku());
        f.setCategoryId(p.getCategoryId());
        f.setBrandId(p.getBrandId());
        f.setDescription(p.getDescription());
        f.setFabric(p.getFabric());
        f.setDesign(p.getDesign());
        f.setPurchaseCost(p.getPurchaseCost());
        f.setSellingPrice(p.getSellingPrice());
        f.setMrp(p.getMrp());
        f.setTaxRate(p.getTaxRate());
        f.setHsnCode(p.getHsnCode());
        f.setMinStock(p.getMinStock());
        f.setBarcode(p.getBarcode());
        f.setActive(p.isActive());
        model.addAttribute("form", f);
        model.addAttribute("editing", true);
        model.addAttribute("productId", id);
        lists(model);
        return "products/form";
    }

    @PostMapping("/products/{id}")
    @PreAuthorize("hasAuthority('PRODUCT_MANAGE')")
    public String update(@PathVariable UUID id, @ModelAttribute("form") ProductForm form,
                         @RequestParam(required = false) MultipartFile image, Model model, RedirectAttributes redirect) {
        try {
            products.update(id, form.toInput(products.get(id).getPurchaseCost()));
            if (image != null && !image.isEmpty()) {
                products.setImage(id, files.storeImage(image));
            }
            redirect.addFlashAttribute("successKey", "product.saved");
            return "redirect:/products/" + id;
        } catch (BusinessException e) {
            model.addAttribute("errorKey", e.getKey());
            model.addAttribute("errorArgs", e.getArgs());
            model.addAttribute("editing", true);
            model.addAttribute("productId", id);
            lists(model);
            return "products/form";
        }
    }

    @PostMapping("/products/{id}/active")
    @PreAuthorize("hasAuthority('PRODUCT_MANAGE')")
    public String active(@PathVariable UUID id, @RequestParam boolean value, RedirectAttributes redirect) {
        products.setProductActive(id, value);
        redirect.addFlashAttribute("successKey", "product.saved");
        return "redirect:/products/" + id;
    }

    @PostMapping("/products/{id}/variants")
    @PreAuthorize("hasAuthority('PRODUCT_MANAGE')")
    public String addVariants(@PathVariable UUID id, @RequestParam(required = false) List<UUID> sizeIds,
                              @RequestParam(required = false) List<UUID> colourIds, RedirectAttributes redirect) {
        products.addVariants(id, sizeIds == null ? List.of() : sizeIds, colourIds == null ? List.of() : colourIds);
        redirect.addFlashAttribute("successKey", "product.variants.added");
        return "redirect:/products/" + id;
    }

    @PostMapping("/products/{id}/variants/{variantId}")
    @PreAuthorize("hasAuthority('PRODUCT_MANAGE')")
    public String updateVariant(@PathVariable UUID id, @PathVariable UUID variantId,
                                @RequestParam(required = false) String barcode,
                                @RequestParam(required = false) BigDecimal priceOverride, RedirectAttributes redirect) {
        products.updateVariant(variantId, barcode, priceOverride);
        redirect.addFlashAttribute("successKey", "product.saved");
        return "redirect:/products/" + id;
    }

    @PostMapping("/products/{id}/variants/{variantId}/active")
    @PreAuthorize("hasAuthority('PRODUCT_MANAGE')")
    public String variantActive(@PathVariable UUID id, @PathVariable UUID variantId, @RequestParam boolean value,
                                RedirectAttributes redirect) {
        products.setVariantActive(variantId, value);
        redirect.addFlashAttribute("successKey", "product.saved");
        return "redirect:/products/" + id;
    }
}
