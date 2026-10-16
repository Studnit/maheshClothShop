package com.maheshclothes.inventory;

import com.maheshclothes.common.PageResult;
import com.maheshclothes.common.SettingsService;
import com.maheshclothes.common.SqlUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Read models for the inventory screens and analytics. Plain SQL: fast, and easy to check in psql. */
@Repository
@RequiredArgsConstructor
public class InventoryQueryRepository {

    /** Alert when stock would last fewer than this many days at the recent selling pace. */
    static final int ALERT_COVER_DAYS = 14;
    /** Suggested purchase tops stock up to this many days of selling. */
    static final int TARGET_COVER_DAYS = 30;
    /** Window used to measure the selling pace. */
    static final int VELOCITY_DAYS = 30;

    private final JdbcTemplate jdbc;
    private final SettingsService settings;

    public int lowThresholdValue() {
        return lowThreshold();
    }

    private int lowThreshold() {
        return settings.integer("stock.low_threshold", 5);
    }

    // ---------- stock list ----------

    public record StockRow(UUID variantId, UUID productId, String productName, String sku, String categoryName,
                           String sizeName, String colourName, String colourHex, int available, int damaged,
                           int minStock, BigDecimal avgCost, BigDecimal value, String status, UUID imageFileId) {
    }

    private static final String STOCK_BASE =
            "select v.id as variant_id, p.id as product_id, p.name as product_name, v.sku, cat.name as category_name, "
                    + "s.name as size_name, c.name as colour_name, c.hex_code, "
                    + "coalesce(sb.available_qty,0) as available, coalesce(sb.damaged_qty,0) as damaged, "
                    + "coalesce(p.min_stock, ?) as min_stock, coalesce(sb.avg_cost,0) as avg_cost, "
                    + "coalesce(sb.available_qty,0) * coalesce(sb.avg_cost,0) as stock_value, "
                    + "case when coalesce(sb.available_qty,0) = 0 then 'OUT' "
                    + "     when coalesce(sb.available_qty,0) <= coalesce(p.min_stock, ?) then 'LOW' else 'OK' end as status, "
                    + "p.image_file_id, coalesce(s.sort_order,0) as size_order "
                    + "from product_variants v "
                    + "join products p on p.id = v.product_id "
                    + "join categories cat on cat.id = p.category_id "
                    + "left join sizes s on s.id = v.size_id "
                    + "left join colours c on c.id = v.colour_id "
                    + "left join stock_balances sb on sb.variant_id = v.id "
                    + "where v.active and p.active";

    public PageResult<StockRow> stockList(String q, UUID categoryId, String status, int page, int size) {
        int low = lowThreshold();
        StringBuilder filter = new StringBuilder();
        List<Object> filterArgs = new ArrayList<>();
        if (categoryId != null) {
            filter.append(" and p.category_id = ?");
            filterArgs.add(categoryId);
        }
        for (String t : SqlUtil.tokens(q)) {
            filter.append(" and (lower(p.name) like ? or lower(v.sku) like ? or lower(coalesce(s.name,'')) like ?"
                    + " or lower(coalesce(c.name,'')) like ? or lower(coalesce(v.barcode,'')) like ?)");
            String like = SqlUtil.like(t);
            for (int k = 0; k < 5; k++) {
                filterArgs.add(like);
            }
        }
        String outer = "";
        if ("low".equals(status)) {
            outer = " where status = 'LOW'";
        } else if ("out".equals(status)) {
            outer = " where status = 'OUT'";
        } else if ("lowout".equals(status)) {
            outer = " where status in ('LOW','OUT')";
        } else if ("damaged".equals(status)) {
            outer = " where damaged > 0";
        }
        String inner = STOCK_BASE + filter;
        List<Object> args = new ArrayList<>();
        args.add(low);
        args.add(low);
        args.addAll(filterArgs);

        Long total = jdbc.queryForObject("select count(*) from (" + inner + ") x" + outer, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(page * size);
        List<StockRow> rows = jdbc.query("select * from (" + inner + ") x" + outer
                        + " order by lower(product_name), colour_name, size_order limit ? offset ?",
                (rs, i) -> new StockRow(SqlUtil.uuid(rs, 1), SqlUtil.uuid(rs, 2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getInt(9), rs.getInt(10),
                        rs.getInt(11), rs.getBigDecimal(12), rs.getBigDecimal(13), rs.getString(14),
                        SqlUtil.uuid(rs, 15)), pageArgs.toArray());
        return new PageResult<>(rows, page, size, total == null ? 0 : total);
    }

    // ---------- summary & health ----------

    public record StockSummary(long variants, long units, long damagedUnits, BigDecimal value, long low, long out,
                               long ok) {
        public long healthyPercent() {
            return variants == 0 ? 0 : Math.round(ok * 100.0 / variants);
        }
    }

    public StockSummary summary() {
        int low = lowThreshold();
        return jdbc.queryForObject("select count(*), coalesce(sum(available),0), coalesce(sum(damaged),0), "
                        + "coalesce(sum(stock_value),0), "
                        + "count(*) filter (where status='LOW'), count(*) filter (where status='OUT'), "
                        + "count(*) filter (where status='OK') from (" + STOCK_BASE + ") x",
                (rs, i) -> new StockSummary(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getBigDecimal(4),
                        rs.getLong(5), rs.getLong(6), rs.getLong(7)), low, low);
    }

    public record CategoryValue(String category, long units, BigDecimal value) {
    }

    public List<CategoryValue> valueByCategory() {
        return jdbc.query("select cat.name, coalesce(sum(sb.available_qty),0), "
                + "coalesce(sum(sb.available_qty * sb.avg_cost),0) "
                + "from categories cat join products p on p.category_id = cat.id and p.active "
                + "join product_variants v on v.product_id = p.id and v.active "
                + "left join stock_balances sb on sb.variant_id = v.id "
                + "group by cat.name having coalesce(sum(sb.available_qty),0) > 0 order by 3 desc",
                (rs, i) -> new CategoryValue(rs.getString(1), rs.getLong(2), rs.getBigDecimal(3)));
    }

    // ---------- reorder suggestions ----------

    public record ReorderRow(UUID variantId, String productName, String sku, String sizeName, String colourName,
                             int available, int minStock, long soldRecently, BigDecimal perDay, BigDecimal daysCover,
                             int suggested) {
    }

    /**
     * Variants that are selling and will run out soon. Pace = units sold (net of returns) in the last 30 days.
     * Suggested quantity tops stock up to 30 days of selling.
     */
    public List<ReorderRow> reorderSuggestions(int limit) {
        int low = lowThreshold();
        String sql = "with sold as ("
                + " select si.variant_id, sum(si.quantity - si.returned_qty) as qty"
                + "   from sales_items si join sales_invoices i on i.id = si.invoice_id"
                + "  where i.status = 'COMPLETED' and i.sale_time >= now() - make_interval(days => " + VELOCITY_DAYS + ")"
                + "  group by si.variant_id) "
                + "select v.id, p.name, v.sku, s.name, c.name, sb.available_qty, coalesce(p.min_stock, ?), so.qty, "
                + "       round(so.qty::numeric / " + VELOCITY_DAYS + ", 2), "
                + "       round(sb.available_qty / (so.qty::numeric / " + VELOCITY_DAYS + "), 1), "
                + "       greatest(0, ceil(so.qty::numeric / " + VELOCITY_DAYS + " * " + TARGET_COVER_DAYS
                + ") - sb.available_qty)::int "
                + "from sold so "
                + "join product_variants v on v.id = so.variant_id and v.active "
                + "join products p on p.id = v.product_id and p.active "
                + "join stock_balances sb on sb.variant_id = v.id "
                + "left join sizes s on s.id = v.size_id "
                + "left join colours c on c.id = v.colour_id "
                + "where so.qty > 0 and (sb.available_qty <= coalesce(p.min_stock, ?) "
                + "   or sb.available_qty / (so.qty::numeric / " + VELOCITY_DAYS + ") < " + ALERT_COVER_DAYS + ") "
                + "order by (sb.available_qty / (so.qty::numeric / " + VELOCITY_DAYS + ")) asc, p.name limit ?";
        return jdbc.query(sql, (rs, i) -> new ReorderRow(SqlUtil.uuid(rs, 1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getInt(6), rs.getInt(7), rs.getLong(8), rs.getBigDecimal(9),
                rs.getBigDecimal(10), rs.getInt(11)), low, low, limit);
    }

    // ---------- dead stock / fast movers ----------

    public record DeadStockRow(UUID variantId, String productName, String sku, String sizeName, String colourName,
                               int available, BigDecimal value, Instant lastSale, int daysIdle) {
    }

    /** Sellable stock that has not sold for the given number of days (cash tied up on the shelf). */
    public List<DeadStockRow> deadStock(int days, int limit) {
        String sql = "select v.id, p.name, v.sku, s.name, c.name, sb.available_qty, "
                + "       sb.available_qty * sb.avg_cost, ls.last_sale, "
                + "       floor(extract(epoch from (now() - coalesce(ls.last_sale, fr.first_in))) / 86400)::int "
                + "from stock_balances sb "
                + "join product_variants v on v.id = sb.variant_id and v.active "
                + "join products p on p.id = v.product_id and p.active "
                + "left join sizes s on s.id = v.size_id "
                + "left join colours c on c.id = v.colour_id "
                + "left join lateral (select max(i.sale_time) as last_sale from sales_items si "
                + "     join sales_invoices i on i.id = si.invoice_id "
                + "     where si.variant_id = v.id and i.status = 'COMPLETED') ls on true "
                + "left join lateral (select min(m.created_at) as first_in from stock_movements m "
                + "     where m.variant_id = v.id and m.available_delta > 0) fr on true "
                + "where sb.available_qty > 0 "
                + "  and coalesce(ls.last_sale, fr.first_in) < now() - make_interval(days => ?) "
                + "order by 7 desc limit ?";
        return jdbc.query(sql, (rs, i) -> new DeadStockRow(SqlUtil.uuid(rs, 1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getInt(6), rs.getBigDecimal(7), SqlUtil.instant(rs, 8),
                rs.getInt(9)), days, limit);
    }

    public record FastMoverRow(UUID variantId, String productName, String sku, String sizeName, String colourName,
                               long sold, int available) {
    }

    public List<FastMoverRow> fastMovers(int days, int limit) {
        String sql = "select v.id, p.name, v.sku, s.name, c.name, sum(si.quantity - si.returned_qty) as sold, "
                + "       coalesce(sb.available_qty, 0) "
                + "from sales_items si "
                + "join sales_invoices i on i.id = si.invoice_id and i.status = 'COMPLETED' "
                + "     and i.sale_time >= now() - make_interval(days => ?) "
                + "join product_variants v on v.id = si.variant_id "
                + "join products p on p.id = v.product_id "
                + "left join sizes s on s.id = v.size_id "
                + "left join colours c on c.id = v.colour_id "
                + "left join stock_balances sb on sb.variant_id = v.id "
                + "group by v.id, p.name, v.sku, s.name, c.name, sb.available_qty "
                + "having sum(si.quantity - si.returned_qty) > 0 "
                + "order by sold desc, p.name limit ?";
        return jdbc.query(sql, (rs, i) -> new FastMoverRow(SqlUtil.uuid(rs, 1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getLong(6), rs.getInt(7)), days, limit);
    }

    // ---------- movement history ----------

    public record MovementRow(UUID id, Instant at, String productName, String sku, String sizeName, String colourName,
                              String type, int availableDelta, int damagedDelta, BigDecimal unitCost,
                              int availableAfter, int damagedAfter, String refNo, String reason, String user) {
    }

    public PageResult<MovementRow> movements(UUID variantId, String type, Instant from, Instant toExclusive,
                                             int page, int size) {
        StringBuilder where = new StringBuilder(" where m.created_at >= ? and m.created_at < ?");
        List<Object> args = new ArrayList<>();
        args.add(SqlUtil.ts(from));
        args.add(SqlUtil.ts(toExclusive));
        if (variantId != null) {
            where.append(" and m.variant_id = ?");
            args.add(variantId);
        }
        if (type != null && !type.isBlank()) {
            where.append(" and m.movement_type = ?");
            args.add(type);
        }
        String join = " from stock_movements m join product_variants v on v.id = m.variant_id "
                + "join products p on p.id = v.product_id left join sizes s on s.id = v.size_id "
                + "left join colours c on c.id = v.colour_id";
        Long total = jdbc.queryForObject("select count(*)" + join + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(page * size);
        List<MovementRow> rows = jdbc.query("select m.id, m.created_at, p.name, v.sku, s.name, c.name, m.movement_type, "
                        + "m.available_delta, m.damaged_delta, m.unit_cost, m.available_after, m.damaged_after, "
                        + "m.ref_no, m.reason, m.created_by" + join + where
                        + " order by m.seq desc limit ? offset ?",
                (rs, i) -> new MovementRow(SqlUtil.uuid(rs, 1), SqlUtil.instant(rs, 2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getInt(8), rs.getInt(9),
                        rs.getBigDecimal(10), rs.getInt(11), rs.getInt(12), rs.getString(13), rs.getString(14),
                        rs.getString(15)), pageArgs.toArray());
        return new PageResult<>(rows, page, size, total == null ? 0 : total);
    }

    // ---------- 14-day stock-in / stock-out trend (for the dashboard) ----------

    public record DayFlow(java.time.LocalDate day, long in, long out) {
    }

    // ---------- size x colour matrix ----------

    /** One cell of the matrix; qty is sellable units. */
    public record MatrixCell(String colour, String colourHex, String size, int sizeOrder, int colourOrder, int qty,
                             int minStock) {
    }

    public List<MatrixCell> matrix(UUID productId) {
        int low = lowThreshold();
        return jdbc.query("select coalesce(c.name,'-'), c.hex_code, coalesce(s.name,'-'), coalesce(s.sort_order,0), "
                        + "coalesce(c.sort_order,0), coalesce(sb.available_qty,0), coalesce(p.min_stock, ?) "
                        + "from product_variants v join products p on p.id = v.product_id "
                        + "left join sizes s on s.id = v.size_id left join colours c on c.id = v.colour_id "
                        + "left join stock_balances sb on sb.variant_id = v.id where v.product_id = ? and v.active",
                (rs, i) -> new MatrixCell(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5),
                        rs.getInt(6), rs.getInt(7)), low, productId);
    }

    /** Products that have stock or sales, for the matrix picker. */
    public record ProductOption(UUID id, String name) {
    }

    public List<ProductOption> productOptions() {
        return jdbc.query("select id, name from products where active order by lower(name)",
                (rs, i) -> new ProductOption(SqlUtil.uuid(rs, 1), rs.getString(2)));
    }
}
