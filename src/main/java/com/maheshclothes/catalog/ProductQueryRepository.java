package com.maheshclothes.catalog;

import com.maheshclothes.common.PageResult;
import com.maheshclothes.common.SqlUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Read models for product screens, the POS search and the purchase form. */
@Repository
@RequiredArgsConstructor
public class ProductQueryRepository {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    /** One sellable variant as shown in the POS / purchase search. */
    public record VariantHit(UUID variantId, UUID productId, String productName, String sku, String sizeName,
                             String colourName, String colourHex, BigDecimal price, BigDecimal mrp,
                             BigDecimal taxRate, String hsnCode, int available, String categoryName,
                             UUID imageFileId, String barcode, BigDecimal purchaseCost) {
    }

    private static final String HIT_SELECT = """
            select v.id, p.id, p.name, v.sku, s.name, c.name, c.hex_code,
                   coalesce(v.selling_price, p.selling_price), p.mrp, p.tax_rate, p.hsn_code,
                   coalesce(sb.available_qty, 0), cat.name, p.image_file_id, coalesce(v.barcode, p.barcode),
                   p.purchase_cost
              from product_variants v
              join products p on p.id = v.product_id
              join categories cat on cat.id = p.category_id
              left join sizes s on s.id = v.size_id
              left join colours c on c.id = v.colour_id
              left join stock_balances sb on sb.variant_id = v.id
            """;

    private static VariantHit hit(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new VariantHit(SqlUtil.uuid(rs, 1), SqlUtil.uuid(rs, 2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getBigDecimal(8), rs.getBigDecimal(9),
                rs.getBigDecimal(10), rs.getString(11), rs.getInt(12), rs.getString(13), SqlUtil.uuid(rs, 14),
                rs.getString(15), rs.getBigDecimal(16));
    }

    /** Free-text search over name, SKU, size, colour, design, fabric and barcode. Every word must match something. */
    public List<VariantHit> searchVariants(String q, UUID categoryId, int limit, boolean includeInactive) {
        StringBuilder sql = new StringBuilder(HIT_SELECT).append(" where 1=1");
        List<Object> args = new ArrayList<>();
        if (!includeInactive) {
            sql.append(" and v.active and p.active");
        }
        if (categoryId != null) {
            sql.append(" and p.category_id = ?");
            args.add(categoryId);
        }
        List<String> tokens = SqlUtil.tokens(q);
        if (!tokens.isEmpty()) {
            sql.append(" and (v.barcode = ? or p.barcode = ? or upper(v.sku) = upper(?) or (1=1");
            String whole = q.trim();
            args.add(whole);
            args.add(whole);
            args.add(whole);
            for (String t : tokens) {
                sql.append("""
                        \s and (lower(p.name) like ? or lower(v.sku) like ? or lower(coalesce(s.name,'')) like ?
                              or lower(coalesce(c.name,'')) like ? or lower(coalesce(p.design,'')) like ?
                              or lower(coalesce(p.fabric,'')) like ? or lower(cat.name) like ?
                              or lower(p.product_code) like ?)""");
                String like = SqlUtil.like(t);
                for (int k = 0; k < 8; k++) {
                    args.add(like);
                }
            }
            sql.append("))");
        }
        sql.append(" order by p.name, coalesce(s.sort_order, 0), c.name limit ?");
        args.add(limit);
        return jdbc.query(sql.toString(), ProductQueryRepository::hit, args.toArray());
    }

    /** Exact barcode / SKU lookup used when a scanner "types" a code and presses Enter. */
    public Optional<VariantHit> findByCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        List<VariantHit> list = jdbc.query(HIT_SELECT
                        + " where v.active and p.active and (v.barcode = ? or p.barcode = ? or upper(v.sku) = upper(?)) limit 2",
                ProductQueryRepository::hit, code.trim(), code.trim(), code.trim());
        return list.size() == 1 ? Optional.of(list.get(0)) : Optional.empty();
    }

    /** Facts about variants the sales and purchase services need. */
    public record VariantInfo(UUID variantId, UUID productId, String productName, String sku, String sizeName,
                              String colourName, String hsnCode, BigDecimal price, BigDecimal taxRate,
                              BigDecimal purchaseCost, boolean active) {
        public String label() {
            StringBuilder sb = new StringBuilder(productName);
            if (colourName != null) {
                sb.append(" / ").append(colourName);
            }
            if (sizeName != null) {
                sb.append(" / ").append(sizeName);
            }
            return sb.toString();
        }
    }

    public Map<UUID, VariantInfo> variantInfo(Collection<UUID> ids) {
        Map<UUID, VariantInfo> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        named.query("""
                select v.id, p.id, p.name, v.sku, s.name, c.name, p.hsn_code,
                       coalesce(v.selling_price, p.selling_price), p.tax_rate, p.purchase_cost, (v.active and p.active)
                  from product_variants v
                  join products p on p.id = v.product_id
                  left join sizes s on s.id = v.size_id
                  left join colours c on c.id = v.colour_id
                 where v.id in (:ids)
                """, new MapSqlParameterSource("ids", ids), rs -> {
            VariantInfo vi = new VariantInfo(SqlUtil.uuid(rs, 1), SqlUtil.uuid(rs, 2), rs.getString(3), rs.getString(4),
                    rs.getString(5), rs.getString(6), rs.getString(7), rs.getBigDecimal(8), rs.getBigDecimal(9),
                    rs.getBigDecimal(10), rs.getBoolean(11));
            out.put(vi.variantId(), vi);
        });
        return out;
    }

    // ---------- product list ----------

    public record ProductFilter(String q, UUID categoryId, UUID brandId, String status) {
    }

    public record ProductRow(UUID id, String code, String name, String sku, String categoryName, String brandName,
                             BigDecimal sellingPrice, BigDecimal purchaseCost, boolean active, long variantCount,
                             long available, long damaged, UUID imageFileId) {
    }

    public PageResult<ProductRow> listProducts(ProductFilter f, int page, int size) {
        StringBuilder where = new StringBuilder(" where 1=1");
        List<Object> args = new ArrayList<>();
        if (f.categoryId() != null) {
            where.append(" and p.category_id = ?");
            args.add(f.categoryId());
        }
        if (f.brandId() != null) {
            where.append(" and p.brand_id = ?");
            args.add(f.brandId());
        }
        if ("active".equals(f.status())) {
            where.append(" and p.active");
        } else if ("inactive".equals(f.status())) {
            where.append(" and not p.active");
        }
        for (String t : SqlUtil.tokens(f.q())) {
            where.append("""
                    \s and (lower(p.name) like ? or lower(p.sku) like ? or lower(p.product_code) like ?
                          or lower(coalesce(p.design,'')) like ? or lower(coalesce(p.fabric,'')) like ?
                          or lower(coalesce(p.barcode,'')) like ?
                          or exists (select 1 from product_variants x where x.product_id = p.id
                                      and (lower(x.sku) like ? or lower(coalesce(x.barcode,'')) like ?)))""");
            String like = SqlUtil.like(t);
            for (int k = 0; k < 8; k++) {
                args.add(like);
            }
        }
        Long total = jdbc.queryForObject("select count(*) from products p" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(page * size);
        List<ProductRow> rows = jdbc.query("""
                select p.id, p.product_code, p.name, p.sku, cat.name, b.name, p.selling_price, p.purchase_cost, p.active,
                       count(v.id), coalesce(sum(sb.available_qty), 0), coalesce(sum(sb.damaged_qty), 0), p.image_file_id
                  from products p
                  join categories cat on cat.id = p.category_id
                  left join brands b on b.id = p.brand_id
                  left join product_variants v on v.product_id = p.id
                  left join stock_balances sb on sb.variant_id = v.id
                """ + where + " " + """
                 group by p.id, cat.name, b.name
                 order by lower(p.name) limit ? offset ?
                """, (rs, i) -> new ProductRow(SqlUtil.uuid(rs, 1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getBigDecimal(7), rs.getBigDecimal(8), rs.getBoolean(9),
                rs.getLong(10), rs.getLong(11), rs.getLong(12), SqlUtil.uuid(rs, 13)), pageArgs.toArray());
        return new PageResult<>(rows, page, size, total == null ? 0 : total);
    }

    // ---------- variants of one product ----------

    public record VariantRow(UUID id, String sku, UUID sizeId, String sizeName, UUID colourId, String colourName,
                             String colourHex, String barcode, BigDecimal priceOverride, boolean active,
                             int available, int damaged, BigDecimal avgCost) {
    }

    public List<VariantRow> variantsOf(UUID productId) {
        return jdbc.query("""
                select v.id, v.sku, v.size_id, s.name, v.colour_id, c.name, c.hex_code, v.barcode, v.selling_price,
                       v.active, coalesce(sb.available_qty, 0), coalesce(sb.damaged_qty, 0), coalesce(sb.avg_cost, 0)
                  from product_variants v
                  left join sizes s on s.id = v.size_id
                  left join colours c on c.id = v.colour_id
                  left join stock_balances sb on sb.variant_id = v.id
                 where v.product_id = ?
                 order by coalesce(c.sort_order, 0), c.name, coalesce(s.sort_order, 0), s.name
                """, (rs, i) -> new VariantRow(SqlUtil.uuid(rs, 1), rs.getString(2), SqlUtil.uuid(rs, 3),
                rs.getString(4), SqlUtil.uuid(rs, 5), rs.getString(6), rs.getString(7), rs.getString(8),
                rs.getBigDecimal(9), rs.getBoolean(10), rs.getInt(11), rs.getInt(12), rs.getBigDecimal(13)), productId);
    }
}
