package com.maheshclothes.salesreturn;

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

@Repository
@RequiredArgsConstructor
public class ReturnQueryRepository {

    private final JdbcTemplate jdbc;

    public record ReturnRow(UUID id, String returnNo, UUID invoiceId, String invoiceNo, Instant time, String type,
                            String reason, BigDecimal total, BigDecimal balanceAdjusted, BigDecimal refunded,
                            BigDecimal credit, BigDecimal creditUsed, String creditStatus, String customer, String by) {
        public BigDecimal creditLeft() {
            return credit.subtract(creditUsed);
        }
    }

    private static final String SELECT = "select r.id, r.return_no, r.invoice_id, i.invoice_no, r.return_time, r.return_type, "
            + "r.reason, r.total, r.balance_adjusted, r.refund_total, r.credit_total, r.credit_used, r.credit_status, "
            + "i.customer_name, r.created_by from sales_returns r join sales_invoices i on i.id = r.invoice_id ";

    private static ReturnRow map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new ReturnRow(SqlUtil.uuid(rs, 1), rs.getString(2), SqlUtil.uuid(rs, 3), rs.getString(4), SqlUtil.instant(rs, 5),
                rs.getString(6), rs.getString(7), rs.getBigDecimal(8), rs.getBigDecimal(9), rs.getBigDecimal(10),
                rs.getBigDecimal(11), rs.getBigDecimal(12), rs.getString(13), rs.getString(14), rs.getString(15));
    }

    public PageResult<ReturnRow> list(Instant from, Instant toExclusive, String q, int page, int size) {
        StringBuilder where = new StringBuilder("where r.return_time >= ? and r.return_time < ?");
        List<Object> args = new ArrayList<>(List.of(SqlUtil.ts(from), SqlUtil.ts(toExclusive)));
        for (String t : SqlUtil.tokens(q)) {
            where.append(" and (lower(r.return_no) like ? or lower(i.invoice_no) like ? or lower(coalesce(i.customer_name,'')) like ?)");
            String like = SqlUtil.like(t);
            args.add(like);
            args.add(like);
            args.add(like);
        }
        Long total = jdbc.queryForObject("select count(*) from sales_returns r join sales_invoices i on i.id = r.invoice_id " + where,
                Long.class, args.toArray());
        List<Object> pa = new ArrayList<>(args);
        pa.add(size);
        pa.add(page * size);
        return new PageResult<>(jdbc.query(SELECT + where + " order by r.return_time desc limit ? offset ?",
                ReturnQueryRepository::map, pa.toArray()), page, size, total == null ? 0 : total);
    }

    public ReturnRow get(UUID id) {
        return jdbc.query(SELECT + "where r.id = ?", ReturnQueryRepository::map, id).stream().findFirst().orElse(null);
    }

    public record ItemRow(String productName, String sku, String sizeName, String colourName, int quantity,
                          BigDecimal amount, String condition) {
    }

    public List<ItemRow> items(UUID returnId) {
        return jdbc.query("select s.product_name, s.sku, s.size_name, s.colour_name, ri.quantity, ri.amount, ri.item_condition "
                        + "from sales_return_items ri join sales_items s on s.id = ri.sales_item_id where ri.return_id = ? "
                        + "order by s.product_name",
                (rs, n) -> new ItemRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5),
                        rs.getBigDecimal(6), rs.getString(7)), returnId);
    }

    /** Credit that can still be used by a replacement sale. */
    public ReturnRow pendingCredit(UUID returnId) {
        ReturnRow r = get(returnId);
        return r != null && "PENDING".equals(r.creditStatus()) && r.creditLeft().signum() > 0 ? r : null;
    }

}
