package com.maheshclothes.purchasing;

import com.maheshclothes.common.PageResult;
import com.maheshclothes.common.SqlUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Read models for supplier and purchase screens. */
@Repository
@RequiredArgsConstructor
public class PurchaseQueryRepository {

    private final JdbcTemplate jdbc;

    // ---------- suppliers ----------

    public record SupplierRow(UUID id, String code, String name, String phone, String gstin, boolean active,
                              BigDecimal balance, long purchases, LocalDate lastPurchase) {
    }

    private static final String SUPPLIER_SELECT =
            "select s.id, s.supplier_code, s.name, s.phone, s.gstin, s.active, "
                    + "s.opening_balance + coalesce(p.total,0) - coalesce(p.returned,0) - coalesce(pay.paid,0) as balance, "
                    + "coalesce(p.cnt,0), p.last_date "
                    + "from suppliers s "
                    + "left join (select supplier_id, sum(total) total, sum(returned_total) returned, count(*) cnt, "
                    + "           max(purchase_date) last_date from purchase_invoices where status = 'RECEIVED' "
                    + "           group by supplier_id) p on p.supplier_id = s.id "
                    + "left join (select supplier_id, sum(amount) paid from supplier_payments group by supplier_id) pay "
                    + "       on pay.supplier_id = s.id ";

    private static SupplierRow supplier(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        java.sql.Date d = rs.getDate(9);
        return new SupplierRow(SqlUtil.uuid(rs, 1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getBoolean(6), rs.getBigDecimal(7), rs.getLong(8), d == null ? null : d.toLocalDate());
    }

    public List<SupplierRow> suppliers(String q, boolean onlyWithBalance) {
        StringBuilder sql = new StringBuilder(SUPPLIER_SELECT).append("where 1=1");
        List<Object> args = new ArrayList<>();
        for (String t : SqlUtil.tokens(q)) {
            sql.append(" and (lower(s.name) like ? or lower(s.supplier_code) like ? or lower(coalesce(s.phone,'')) like ?)");
            String like = SqlUtil.like(t);
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (onlyWithBalance) {
            sql.append(" and s.opening_balance + coalesce(p.total,0) - coalesce(p.returned,0) - coalesce(pay.paid,0) <> 0");
        }
        sql.append(" order by lower(s.name)");
        return jdbc.query(sql.toString(), PurchaseQueryRepository::supplier, args.toArray());
    }

    public SupplierRow supplier(UUID id) {
        List<SupplierRow> l = jdbc.query(SUPPLIER_SELECT + "where s.id = ?", PurchaseQueryRepository::supplier, id);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Total we owe suppliers (suppliers with a positive balance only). */
    public BigDecimal totalPayable() {
        BigDecimal v = jdbc.queryForObject("select coalesce(sum(x.balance),0) from ("
                + SUPPLIER_SELECT.replace("s.id, s.supplier_code", "s.id, s.supplier_code") + ") x where x.balance > 0",
                BigDecimal.class);
        return v == null ? BigDecimal.ZERO : v;
    }

    public record LedgerRow(LocalDate date, String type, String ref, BigDecimal amount, BigDecimal running) {
    }

    /** Supplier statement: opening balance, purchases (+), returns (-) and payments (-), with a running balance. */
    public List<LedgerRow> ledger(UUID supplierId) {
        String sql = "with ev as ("
                + " select s.created_at::date as d, 0 as ord, 'OPENING' as type, '' as ref, s.opening_balance as amount"
                + "   from suppliers s where s.id = ? "
                + " union all select purchase_date, 1, 'PURCHASE', purchase_no, total from purchase_invoices"
                + "   where supplier_id = ? and status = 'RECEIVED' "
                + " union all select return_date, 2, 'RETURN', return_no, -total from supplier_returns where supplier_id = ? "
                + " union all select paid_on, 3, 'PAYMENT', payment_no, -amount from supplier_payments where supplier_id = ?) "
                + "select d, type, ref, amount, sum(amount) over (order by d, ord, ref rows between unbounded preceding and current row) "
                + "from ev order by d, ord, ref";
        return jdbc.query(sql, (rs, i) -> new LedgerRow(rs.getDate(1).toLocalDate(), rs.getString(2), rs.getString(3),
                rs.getBigDecimal(4), rs.getBigDecimal(5)), supplierId, supplierId, supplierId, supplierId);
    }

    // ---------- purchases ----------

    public record PurchaseRow(UUID id, String purchaseNo, UUID supplierId, String supplierName, String supplierInvoiceNo,
                              LocalDate date, String status, BigDecimal total, BigDecimal paid, BigDecimal returned,
                              BigDecimal outstanding, long lines) {
    }

    public PageResult<PurchaseRow> purchases(UUID supplierId, String status, LocalDate from, LocalDate to, String q,
                                             int page, int size) {
        StringBuilder where = new StringBuilder(" where p.purchase_date >= ? and p.purchase_date <= ?");
        List<Object> args = new ArrayList<>();
        args.add(from);
        args.add(to);
        if (supplierId != null) {
            where.append(" and p.supplier_id = ?");
            args.add(supplierId);
        }
        if (status != null && !status.isBlank()) {
            where.append(" and p.status = ?");
            args.add(status);
        }
        for (String t : SqlUtil.tokens(q)) {
            where.append(" and (lower(p.purchase_no) like ? or lower(p.supplier_invoice_no) like ? or lower(s.name) like ?)");
            String like = SqlUtil.like(t);
            args.add(like);
            args.add(like);
            args.add(like);
        }
        String join = " from purchase_invoices p join suppliers s on s.id = p.supplier_id";
        Long total = jdbc.queryForObject("select count(*)" + join + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(page * size);
        List<PurchaseRow> rows = jdbc.query("select p.id, p.purchase_no, p.supplier_id, s.name, p.supplier_invoice_no, "
                        + "p.purchase_date, p.status, p.total, p.amount_paid, p.returned_total, "
                        + "p.total - p.returned_total - p.amount_paid, "
                        + "(select count(*) from purchase_items x where x.purchase_id = p.id)" + join + where
                        + " order by p.purchase_date desc, p.created_at desc limit ? offset ?",
                (rs, i) -> new PurchaseRow(SqlUtil.uuid(rs, 1), rs.getString(2), SqlUtil.uuid(rs, 3), rs.getString(4),
                        rs.getString(5), rs.getDate(6).toLocalDate(), rs.getString(7), rs.getBigDecimal(8),
                        rs.getBigDecimal(9), rs.getBigDecimal(10), rs.getBigDecimal(11), rs.getLong(12)),
                pageArgs.toArray());
        return new PageResult<>(rows, page, size, total == null ? 0 : total);
    }

    public record ItemRow(UUID id, UUID variantId, String productName, String sku, String sizeName, String colourName,
                          int quantity, BigDecimal unitPrice, BigDecimal discount, BigDecimal taxRate,
                          BigDecimal taxAmount, BigDecimal lineTotal, BigDecimal landedUnitCost, int returnedQty) {
    }

    public List<ItemRow> items(UUID purchaseId) {
        return jdbc.query("select i.id, i.variant_id, p.name, v.sku, s.name, c.name, i.quantity, i.unit_price, i.discount, "
                        + "i.tax_rate, i.tax_amount, i.line_total, i.landed_unit_cost, i.returned_qty "
                        + "from purchase_items i join product_variants v on v.id = i.variant_id "
                        + "join products p on p.id = v.product_id left join sizes s on s.id = v.size_id "
                        + "left join colours c on c.id = v.colour_id where i.purchase_id = ? order by i.created_at, p.name",
                (rs, i) -> new ItemRow(SqlUtil.uuid(rs, 1), SqlUtil.uuid(rs, 2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getInt(7), rs.getBigDecimal(8), rs.getBigDecimal(9),
                        rs.getBigDecimal(10), rs.getBigDecimal(11), rs.getBigDecimal(12), rs.getBigDecimal(13),
                        rs.getInt(14)), purchaseId);
    }

    public record PaymentRow(UUID id, String paymentNo, LocalDate paidOn, BigDecimal amount, String method,
                             String reference, String purchaseNo) {
    }

    public List<PaymentRow> paymentsOf(UUID supplierId, UUID purchaseId) {
        StringBuilder sql = new StringBuilder("select sp.id, sp.payment_no, sp.paid_on, sp.amount, sp.method, sp.reference, "
                + "pi.purchase_no from supplier_payments sp left join purchase_invoices pi on pi.id = sp.purchase_id "
                + "where sp.supplier_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(supplierId);
        if (purchaseId != null) {
            sql.append(" and sp.purchase_id = ?");
            args.add(purchaseId);
        }
        sql.append(" order by sp.paid_on desc, sp.created_at desc");
        return jdbc.query(sql.toString(), (rs, i) -> new PaymentRow(SqlUtil.uuid(rs, 1), rs.getString(2),
                rs.getDate(3).toLocalDate(), rs.getBigDecimal(4), rs.getString(5), rs.getString(6), rs.getString(7)),
                args.toArray());
    }

    public record ReturnRow(UUID id, String returnNo, LocalDate date, BigDecimal total, String reason) {
    }

    public List<ReturnRow> returnsOf(UUID purchaseId) {
        return jdbc.query("select id, return_no, return_date, total, reason from supplier_returns "
                        + "where purchase_id = ? order by return_date desc, created_at desc",
                (rs, i) -> new ReturnRow(SqlUtil.uuid(rs, 1), rs.getString(2), rs.getDate(3).toLocalDate(),
                        rs.getBigDecimal(4), rs.getString(5)), purchaseId);
    }

    public record RecentPurchase(UUID id, String purchaseNo, String supplierName, Instant at, BigDecimal total, String status) {
    }

    public List<RecentPurchase> recent(int limit) {
        return jdbc.query("select p.id, p.purchase_no, s.name, p.created_at, p.total, p.status from purchase_invoices p "
                        + "join suppliers s on s.id = p.supplier_id where p.status <> 'CANCELLED' "
                        + "order by p.created_at desc limit ?",
                (rs, i) -> new RecentPurchase(SqlUtil.uuid(rs, 1), rs.getString(2), rs.getString(3),
                        SqlUtil.instant(rs, 4), rs.getBigDecimal(5), rs.getString(6)), limit);
    }
}
