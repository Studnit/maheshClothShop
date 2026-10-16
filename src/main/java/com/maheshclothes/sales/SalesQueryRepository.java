package com.maheshclothes.sales;

import com.maheshclothes.common.PageResult;
import com.maheshclothes.common.SqlUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Read models for invoices, payments and customers. */
@Repository
@RequiredArgsConstructor
public class SalesQueryRepository {

    private final JdbcTemplate jdbc;

    public record InvoiceRow(UUID id, String invoiceNo, Instant saleTime, String customerName, String customerPhone,
                             String status, BigDecimal total, BigDecimal paid, BigDecimal balance, String cashier,
                             long lines, String waStatus) {
    }

    /** Filters for the invoice list; null/blank means "any". */
    public record InvoiceFilter(Instant from, Instant toExclusive, String q, String status, UUID customerId,
                                String cashier, boolean onlyDue) {
    }

    public PageResult<InvoiceRow> invoices(InvoiceFilter f, int page, int size) {
        StringBuilder where = new StringBuilder(" where i.sale_time >= ? and i.sale_time < ?");
        List<Object> args = new ArrayList<>();
        args.add(java.sql.Timestamp.from(f.from()));
        args.add(java.sql.Timestamp.from(f.toExclusive()));
        for (String t : SqlUtil.tokens(f.q())) {
            where.append(" and (lower(i.invoice_no) like ? or lower(coalesce(i.customer_name,'')) like ? "
                    + "or coalesce(i.customer_phone,'') like ?)");
            String like = SqlUtil.like(t);
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (f.status() != null && !f.status().isBlank()) {
            where.append(" and i.status = ?");
            args.add(f.status());
        }
        if (f.customerId() != null) {
            where.append(" and i.customer_id = ?");
            args.add(f.customerId());
        }
        if (f.cashier() != null && !f.cashier().isBlank()) {
            where.append(" and i.created_by = ?");
            args.add(f.cashier());
        }
        if (f.onlyDue()) {
            where.append(" and i.balance_due > 0 and i.status = 'COMPLETED'");
        }
        Long total = jdbc.queryForObject("select count(*) from sales_invoices i" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(page * size);
        List<InvoiceRow> rows = jdbc.query("select i.id, i.invoice_no, i.sale_time, i.customer_name, i.customer_phone, "
                        + "i.status, i.total, i.amount_paid, i.balance_due, i.created_by, "
                        + "(select count(*) from sales_items x where x.invoice_id = i.id), "
                        + "(select w.status from whatsapp_messages w where w.invoice_id = i.id order by w.created_at desc limit 1) "
                        + "from sales_invoices i" + where + " order by i.sale_time desc limit ? offset ?",
                (rs, n) -> new InvoiceRow(SqlUtil.uuid(rs, 1), rs.getString(2), SqlUtil.instant(rs, 3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getBigDecimal(7), rs.getBigDecimal(8), rs.getBigDecimal(9),
                        rs.getString(10), rs.getLong(11), rs.getString(12)), pageArgs.toArray());
        return new PageResult<>(rows, page, size, total == null ? 0 : total);
    }

    public record LineRow(UUID id, UUID variantId, String productName, String sku, String sizeName, String colourName,
                          String hsnCode, int quantity, BigDecimal unitPrice, BigDecimal lineDiscount,
                          BigDecimal invoiceDiscountShare, BigDecimal taxableValue, BigDecimal taxRate,
                          BigDecimal taxAmount, BigDecimal lineTotal, int returnedQty, BigDecimal returnedTotal) {
        public int returnable() {
            return quantity - returnedQty;
        }
    }

    public List<LineRow> lines(UUID invoiceId) {
        return jdbc.query("select id, variant_id, product_name, sku, size_name, colour_name, hsn_code, quantity, unit_price, "
                        + "line_discount, invoice_discount_share, taxable_value, tax_rate, tax_amount, line_total, "
                        + "returned_qty, returned_total from sales_items where invoice_id = ? order by created_at, product_name",
                (rs, n) -> new LineRow(SqlUtil.uuid(rs, 1), SqlUtil.uuid(rs, 2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getInt(8), rs.getBigDecimal(9),
                        rs.getBigDecimal(10), rs.getBigDecimal(11), rs.getBigDecimal(12), rs.getBigDecimal(13),
                        rs.getBigDecimal(14), rs.getBigDecimal(15), rs.getInt(16), rs.getBigDecimal(17)), invoiceId);
    }

    public record PaymentRow(String kind, String method, String methodName, BigDecimal amount, String reference,
                             Instant at) {
    }

    public List<PaymentRow> paymentsOf(UUID invoiceId) {
        return jdbc.query("select p.payment_kind, p.method, m.name, p.amount, p.reference, p.received_at "
                        + "from sales_payments p join payment_methods m on m.code = p.method "
                        + "where p.invoice_id = ? order by p.received_at, p.created_at",
                (rs, n) -> new PaymentRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4),
                        rs.getString(5), SqlUtil.instant(rs, 6)), invoiceId);
    }

    public record RefundRow(String refundNo, String type, BigDecimal amount, String method, Instant at) {
    }

    public List<RefundRow> refundsOf(UUID invoiceId) {
        return jdbc.query("select refund_no, refund_type, amount, method, refunded_at from refunds "
                        + "where invoice_id = ? order by refunded_at",
                (rs, n) -> new RefundRow(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getString(4),
                        SqlUtil.instant(rs, 5)), invoiceId);
    }

    public record PaymentMethodRow(String code, String name, boolean requiresReference) {
    }

    public List<PaymentMethodRow> selectableMethods() {
        return jdbc.query("select code, name, requires_reference from payment_methods "
                        + "where active and selectable order by sort_order, name",
                (rs, n) -> new PaymentMethodRow(rs.getString(1), rs.getString(2), rs.getBoolean(3)));
    }

    // ---------- customers ----------

    public record CustomerRow(UUID id, String code, String name, String phone, boolean active, BigDecimal creditLimit,
                              BigDecimal outstanding, BigDecimal lifetimeValue, long invoices, Instant lastPurchase) {
    }

    private static final String CUSTOMER_SELECT = "select c.id, c.customer_code, c.name, c.phone, c.active, c.credit_limit, "
            + "coalesce(s.due,0), coalesce(s.net,0), coalesce(s.cnt,0), s.last_time from customers c "
            + "left join (select customer_id, sum(balance_due) due, sum(total - returned) net, count(*) cnt, max(sale_time) last_time "
            + "  from (select i.customer_id, i.balance_due, i.total, i.sale_time, "
            + "          coalesce((select sum(returned_total) from sales_items x where x.invoice_id = i.id),0) returned "
            + "        from sales_invoices i where i.status = 'COMPLETED') q group by customer_id) s on s.customer_id = c.id ";

    private static CustomerRow customer(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new CustomerRow(SqlUtil.uuid(rs, 1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5),
                rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getBigDecimal(8), rs.getLong(9), SqlUtil.instant(rs, 10));
    }

    public List<CustomerRow> customers(String q, boolean onlyDue, int limit) {
        StringBuilder sql = new StringBuilder(CUSTOMER_SELECT).append("where 1=1");
        List<Object> args = new ArrayList<>();
        for (String t : SqlUtil.tokens(q)) {
            sql.append(" and (lower(c.name) like ? or lower(c.customer_code) like ? or coalesce(c.phone,'') like ?)");
            String like = SqlUtil.like(t);
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (onlyDue) {
            sql.append(" and coalesce(s.due,0) > 0");
        }
        sql.append(" order by lower(c.name) limit ?");
        args.add(limit);
        return jdbc.query(sql.toString(), SalesQueryRepository::customer, args.toArray());
    }

    public CustomerRow customer(UUID id) {
        List<CustomerRow> l = jdbc.query(CUSTOMER_SELECT + "where c.id = ?", SalesQueryRepository::customer, id);
        return l.isEmpty() ? null : l.get(0);
    }

    public BigDecimal totalReceivable() {
        BigDecimal v = jdbc.queryForObject("select coalesce(sum(balance_due),0) from sales_invoices where status = 'COMPLETED'",
                BigDecimal.class);
        return v == null ? BigDecimal.ZERO : v;
    }
}
