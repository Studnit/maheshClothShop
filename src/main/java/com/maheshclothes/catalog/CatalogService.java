package com.maheshclothes.catalog;

import com.maheshclothes.common.AuditService;
import com.maheshclothes.common.BusinessException;
import com.maheshclothes.common.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** The four configurable lists: categories, brands, sizes, colours. Nothing is ever deleted, only deactivated. */
@Service
@RequiredArgsConstructor
public class CatalogService {

    public enum Type {
        categories, brands, sizes, colours;

        public static Type of(String s) {
            try {
                return valueOf(s);
            } catch (IllegalArgumentException e) {
                throw new NotFoundException("List");
            }
        }
    }

    private final CategoryRepository categories;
    private final BrandRepository brands;
    private final SizeRepository sizes;
    private final ColourRepository colours;
    private final AuditService audit;

    public List<? extends CatalogItem> list(Type type) {
        return switch (type) {
            case categories -> categories.findAllByOrderBySortOrderAscNameAsc();
            case brands -> brands.findAllByOrderBySortOrderAscNameAsc();
            case sizes -> sizes.findAllByOrderBySortOrderAscNameAsc();
            case colours -> colours.findAllByOrderBySortOrderAscNameAsc();
        };
    }

    public List<Category> activeCategories() {
        return categories.findAllByActiveTrueOrderBySortOrderAscNameAsc();
    }

    public List<Brand> activeBrands() {
        return brands.findAllByActiveTrueOrderBySortOrderAscNameAsc();
    }

    public List<Size> activeSizes() {
        return sizes.findAllByActiveTrueOrderBySortOrderAscNameAsc();
    }

    public List<Colour> activeColours() {
        return colours.findAllByActiveTrueOrderBySortOrderAscNameAsc();
    }

    @Transactional
    public void save(Type type, UUID id, String rawName, int sortOrder, String hex) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) {
            throw new BusinessException("catalog.error.name");
        }
        switch (type) {
            case categories -> {
                Category c = id == null ? new Category() : categories.findById(id).orElseThrow(() -> new NotFoundException("Category"));
                checkDuplicate(categories.findByNameIgnoreCase(name).map(Category::getId).orElse(null), id);
                fill(c, name, sortOrder);
                audit.log(id == null ? "CATEGORY_CREATED" : "CATEGORY_UPDATED", "category", categories.save(c).getId(), name);
            }
            case brands -> {
                Brand b = id == null ? new Brand() : brands.findById(id).orElseThrow(() -> new NotFoundException("Brand"));
                checkDuplicate(brands.findByNameIgnoreCase(name).map(Brand::getId).orElse(null), id);
                fill(b, name, sortOrder);
                audit.log(id == null ? "BRAND_CREATED" : "BRAND_UPDATED", "brand", brands.save(b).getId(), name);
            }
            case sizes -> {
                Size s = id == null ? new Size() : sizes.findById(id).orElseThrow(() -> new NotFoundException("Size"));
                checkDuplicate(sizes.findByNameIgnoreCase(name).map(Size::getId).orElse(null), id);
                fill(s, name, sortOrder);
                audit.log(id == null ? "SIZE_CREATED" : "SIZE_UPDATED", "size", sizes.save(s).getId(), name);
            }
            case colours -> {
                Colour c = id == null ? new Colour() : colours.findById(id).orElseThrow(() -> new NotFoundException("Colour"));
                checkDuplicate(colours.findByNameIgnoreCase(name).map(Colour::getId).orElse(null), id);
                fill(c, name, sortOrder);
                String h = hex == null ? "" : hex.trim();
                if (!h.isEmpty() && !h.matches("^#[0-9A-Fa-f]{6}$")) {
                    throw new BusinessException("catalog.error.hex");
                }
                c.setHexCode(h.isEmpty() ? null : h);
                audit.log(id == null ? "COLOUR_CREATED" : "COLOUR_UPDATED", "colour", colours.save(c).getId(), name);
            }
        }
    }

    @Transactional
    public void toggle(Type type, UUID id) {
        switch (type) {
            case categories -> {
                Category c = categories.findById(id).orElseThrow(() -> new NotFoundException("Category"));
                c.setActive(!c.isActive());
                categories.save(c);
            }
            case brands -> {
                Brand b = brands.findById(id).orElseThrow(() -> new NotFoundException("Brand"));
                b.setActive(!b.isActive());
                brands.save(b);
            }
            case sizes -> {
                Size s = sizes.findById(id).orElseThrow(() -> new NotFoundException("Size"));
                s.setActive(!s.isActive());
                sizes.save(s);
            }
            case colours -> {
                Colour c = colours.findById(id).orElseThrow(() -> new NotFoundException("Colour"));
                c.setActive(!c.isActive());
                colours.save(c);
            }
        }
        audit.log("CATALOG_TOGGLED", type.name(), id, null);
    }

    private static void fill(CatalogItem item, String name, int sortOrder) {
        item.setName(name);
        item.setSortOrder(sortOrder);
    }

    private static void checkDuplicate(UUID existingId, UUID editingId) {
        if (existingId != null && !existingId.equals(editingId)) {
            throw new BusinessException("catalog.error.duplicate");
        }
    }
}
