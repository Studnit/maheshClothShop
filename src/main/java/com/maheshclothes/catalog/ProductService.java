package com.maheshclothes.catalog;

import com.maheshclothes.common.AuditService;
import com.maheshclothes.common.BusinessException;
import com.maheshclothes.common.NotFoundException;
import com.maheshclothes.common.NumberingService;
import com.maheshclothes.common.SettingsService;
import com.maheshclothes.inventory.StockBalance;
import com.maheshclothes.inventory.StockBalanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ProductService {

    private static final Pattern SKU = Pattern.compile("^[A-Za-z0-9._-]{2,40}$");

    private final ProductRepository products;
    private final ProductVariantRepository variants;
    private final CategoryRepository categories;
    private final SizeRepository sizes;
    private final ColourRepository colours;
    private final StockBalanceRepository balances;
    private final NumberingService numbering;
    private final SettingsService settings;
    private final AuditService audit;

    /** Everything the owner can type on the product form. */
    public record ProductInput(String name, String sku, UUID categoryId, UUID brandId, String description,
                               String fabric, String design, BigDecimal purchaseCost, BigDecimal sellingPrice,
                               BigDecimal mrp, BigDecimal taxRate, String hsnCode, Integer minStock, String barcode,
                               boolean active) {
    }

    public Product get(UUID id) {
        return products.findById(id).orElseThrow(() -> new NotFoundException("Product"));
    }

    @Transactional
    public Product create(ProductInput in, List<UUID> sizeIds, List<UUID> colourIds) {
        validate(in, null);
        Product p = new Product();
        p.setProductCode(numbering.nextCode("PRODUCT", "P", 5));
        apply(p, in);
        String sku = in.sku() == null || in.sku().isBlank() ? p.getProductCode() : in.sku().trim().toUpperCase();
        p.setSku(sku);
        p = products.save(p);
        addVariantsInternal(p, sizeIds, colourIds);
        audit.log("PRODUCT_CREATED", "product", p.getId(), p.getProductCode() + " " + p.getName());
        return p;
    }

    @Transactional
    public Product update(UUID id, ProductInput in) {
        Product p = get(id);
        validate(in, id);
        apply(p, in);
        if (in.sku() != null && !in.sku().isBlank()) {
            p.setSku(in.sku().trim().toUpperCase());
        }
        audit.log("PRODUCT_UPDATED", "product", id, p.getName());
        return products.save(p);
    }

    private void apply(Product p, ProductInput in) {
        p.setName(in.name().trim());
        p.setCategoryId(in.categoryId());
        p.setBrandId(in.brandId());
        p.setDescription(blank(in.description()));
        p.setFabric(blank(in.fabric()));
        p.setDesign(blank(in.design()));
        p.setPurchaseCost(nz(in.purchaseCost()));
        p.setSellingPrice(in.sellingPrice());
        p.setMrp(in.mrp());
        p.setTaxRate(in.taxRate() != null ? in.taxRate() : settings.decimal("tax.default_rate", BigDecimal.ZERO));
        p.setHsnCode(blank(in.hsnCode()));
        p.setMinStock(in.minStock());
        p.setBarcode(blank(in.barcode()));
        p.setActive(in.active());
    }

    private void validate(ProductInput in, UUID editingId) {
        if (in.name() == null || in.name().isBlank()) {
            throw new BusinessException("product.error.name");
        }
        if (in.categoryId() == null || !categories.existsById(in.categoryId())) {
            throw new BusinessException("product.error.category");
        }
        if (in.sellingPrice() == null || in.sellingPrice().signum() < 0) {
            throw new BusinessException("product.error.price");
        }
        // Selling above the printed MRP is not allowed under Indian legal-metrology rules.
        if (in.mrp() != null && in.sellingPrice().compareTo(in.mrp()) > 0) {
            throw new BusinessException("product.error.mrp");
        }
        if (in.taxRate() != null && (in.taxRate().signum() < 0 || in.taxRate().compareTo(new BigDecimal("100")) > 0)) {
            throw new BusinessException("product.error.tax");
        }
        if (in.minStock() != null && in.minStock() < 0) {
            throw new BusinessException("product.error.minstock");
        }
        if (in.sku() != null && !in.sku().isBlank()) {
            if (!SKU.matcher(in.sku().trim()).matches()) {
                throw new BusinessException("product.error.sku.format");
            }
            boolean taken = editingId == null
                    ? products.existsBySkuIgnoreCase(in.sku().trim())
                    : products.existsBySkuIgnoreCaseAndIdNot(in.sku().trim(), editingId);
            if (taken) {
                throw new BusinessException("product.error.sku.taken");
            }
        }
        String barcode = blank(in.barcode());
        if (barcode != null) {
            boolean usedByProduct = editingId == null
                    ? products.existsByBarcode(barcode)
                    : products.existsByBarcodeAndIdNot(barcode, editingId);
            if (usedByProduct || variants.existsByBarcode(barcode)) {
                throw new BusinessException("product.error.barcode.taken");
            }
        }
    }

    @Transactional
    public List<ProductVariant> addVariants(UUID productId, List<UUID> sizeIds, List<UUID> colourIds) {
        Product p = get(productId);
        List<ProductVariant> created = addVariantsInternal(p, sizeIds, colourIds);
        if (created.isEmpty()) {
            throw new BusinessException("product.error.variants.none");
        }
        audit.log("VARIANTS_ADDED", "product", productId, created.size() + " variant(s)");
        return created;
    }

    /** Creates a variant (and its empty stock row) for every size x colour that does not exist yet. */
    private List<ProductVariant> addVariantsInternal(Product p, List<UUID> sizeIds, List<UUID> colourIds) {
        List<UUID> sz = sizeIds == null || sizeIds.isEmpty() ? java.util.Collections.singletonList(null) : sizeIds;
        List<UUID> cl = colourIds == null || colourIds.isEmpty() ? java.util.Collections.singletonList(null) : colourIds;
        List<ProductVariant> existing = variants.findByProductId(p.getId());
        List<ProductVariant> created = new ArrayList<>();
        for (UUID sizeId : sz) {
            for (UUID colourId : cl) {
                boolean exists = existing.stream().anyMatch(v ->
                        java.util.Objects.equals(v.getSizeId(), sizeId) && java.util.Objects.equals(v.getColourId(), colourId));
                if (exists) {
                    continue;
                }
                ProductVariant v = new ProductVariant();
                v.setProductId(p.getId());
                v.setSizeId(sizeId);
                v.setColourId(colourId);
                v.setSku(uniqueVariantSku(p, sizeId, colourId));
                v = variants.save(v);
                balances.save(new StockBalance(v.getId()));
                created.add(v);
                existing.add(v);
            }
        }
        return created;
    }

    private String uniqueVariantSku(Product p, UUID sizeId, UUID colourId) {
        StringBuilder sb = new StringBuilder(p.getSku());
        if (colourId != null) {
            String c = colours.findById(colourId).orElseThrow(() -> new NotFoundException("Colour")).getName();
            sb.append('-').append(abbreviate(c, 3));
        }
        if (sizeId != null) {
            String s = sizes.findById(sizeId).orElseThrow(() -> new NotFoundException("Size")).getName();
            sb.append('-').append(abbreviate(s, 8));
        }
        String base = sb.toString();
        String sku = base;
        int n = 2;
        while (variants.existsBySkuIgnoreCase(sku)) {
            sku = base + "-" + n++;
        }
        return sku;
    }

    static String abbreviate(String s, int max) {
        String a = s.toUpperCase().replaceAll("[^A-Z0-9]", "");
        if (a.isEmpty()) {
            a = "X";
        }
        return a.length() > max ? a.substring(0, max) : a;
    }

    @Transactional
    public void setProductActive(UUID id, boolean active) {
        Product p = get(id);
        p.setActive(active);
        products.save(p);
        audit.log(active ? "PRODUCT_ACTIVATED" : "PRODUCT_DEACTIVATED", "product", id, p.getName());
    }

    @Transactional
    public void setVariantActive(UUID variantId, boolean active) {
        ProductVariant v = variants.findById(variantId).orElseThrow(() -> new NotFoundException("Variant"));
        v.setActive(active);
        variants.save(v);
        audit.log(active ? "VARIANT_ACTIVATED" : "VARIANT_DEACTIVATED", "variant", variantId, v.getSku());
    }

    @Transactional
    public void updateVariant(UUID variantId, String barcode, BigDecimal priceOverride) {
        ProductVariant v = variants.findById(variantId).orElseThrow(() -> new NotFoundException("Variant"));
        String b = blank(barcode);
        if (b != null && !b.equals(v.getBarcode())) {
            boolean used = variants.existsByBarcode(b) || products.existsByBarcode(b);
            if (used) {
                throw new BusinessException("product.error.barcode.taken");
            }
        }
        if (priceOverride != null && priceOverride.signum() < 0) {
            throw new BusinessException("product.error.price");
        }
        Product p = get(v.getProductId());
        if (priceOverride != null && p.getMrp() != null && priceOverride.compareTo(p.getMrp()) > 0) {
            throw new BusinessException("product.error.mrp");
        }
        v.setBarcode(b);
        v.setSellingPrice(priceOverride);
        variants.save(v);
        audit.log("VARIANT_UPDATED", "variant", variantId, v.getSku());
    }

    @Transactional
    public void setImage(UUID productId, UUID fileId) {
        Product p = get(productId);
        p.setImageFileId(fileId);
        products.save(p);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
