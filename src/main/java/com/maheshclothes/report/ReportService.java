package com.maheshclothes.report;

import com.maheshclothes.common.BusinessException;
import com.maheshclothes.common.CsvUtil;
import com.maheshclothes.common.ShopClock;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Data-driven reports: each one is a SQL query over a date range plus column metadata. The screen, the CSV export
 * and the permission check are all generated from the same definition, so they can never disagree.
 *
 * Every query uses two bind values for the range, in this order: [from, toExclusive) as timestamps for sales time
 * columns, or as dates for date columns (see {@link Range}).
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    public enum ColType { TEXT, MONEY, NUMBER, DATE, PERCENT }

    public record Column(String key, ColType type) {
    }

    public enum Range { TIMESTAMP, DATE, NONE }

    public record Definition(String key, String group, String permission, Range range, List<Column> columns, String sql,
                             int rangeParams) {
    }

    public record Result(Definition definition, LocalDate from, LocalDate to, List<List<Object>> rows,
                         List<Object> totals) {
    }

    private final JdbcTemplate jdbc;
    private final ShopClock clock;

    private static Column t(String k) {
        return new Column(k, ColType.TEXT);
    }

    private static Column m(String k) {
        return new Column(k, ColType.MONEY);
    }

    private static Column n(String k) {
        return new Column(k, ColType.NUMBER);
    }

    private static Column d(String k) {
        return new Column(k, ColType.DATE);
    }

    private static Column p(String k) {
        return new Column(k, ColType.PERCENT);
    }

    /** Net of returns, completed invoices only. */
    private static final String LINE_NET = "(s.line_total - s.returned_total)";
    private static final String LINE_PROFIT = "(s.taxable_value - s.returned_taxable - s.unit_cost * (s.quantity - s.returned_qty))";
    private static final String SALES_FROM = " from sales_items s join sales_invoices i on i.id = s.invoice_id "
            + "where i.status = 'COMPLETED' and i.sale_time >= ? and i.sale_time < ? ";

    private static final Map<String, Definition> DEFS = new LinkedHashMap<>();

    private static void add(String key, String group, String perm, Range range, int rangeParams, String sql, Column... cols) {
        DEFS.put(key, new Definition(key, group, perm, range, List.of(cols), sql, rangeParams));
    }

    static {
        add("daily_sales", "sales", "REPORT_SALES", Range.TIMESTAMP, 2,
                "select (i.sale_time at time zone 'Asia/Kolkata')::date, count(distinct i.id), sum(s.quantity - s.returned_qty), "
                        + "sum(" + LINE_NET + "), sum(" + LINE_PROFIT + ")" + SALES_FROM + "group by 1 order by 1 desc",
                d("date"), n("bills"), n("units"), m("sales"), m("profit"));
        add("sales_by_product", "sales", "REPORT_SALES", Range.TIMESTAMP, 2,
                "select s.product_name, sum(s.quantity - s.returned_qty), sum(" + LINE_NET + "), sum(" + LINE_PROFIT + ")"
                        + SALES_FROM + "group by s.product_name having sum(s.quantity - s.returned_qty) > 0 order by 3 desc",
                t("product"), n("units"), m("sales"), m("profit"));
        add("sales_by_category", "sales", "REPORT_SALES", Range.TIMESTAMP, 2,
                "select coalesce(c.name,'-'), sum(s.quantity - s.returned_qty), sum(" + LINE_NET + "), sum(" + LINE_PROFIT + ") "
                        + "from sales_items s join sales_invoices i on i.id = s.invoice_id "
                        + "join product_variants v on v.id = s.variant_id join products p on p.id = v.product_id "
                        + "left join categories c on c.id = p.category_id "
                        + "where i.status = 'COMPLETED' and i.sale_time >= ? and i.sale_time < ? group by 1 order by 3 desc",
                t("category"), n("units"), m("sales"), m("profit"));
        add("sales_by_cashier", "sales", "REPORT_SALES", Range.TIMESTAMP, 2,
                "select coalesce(i.created_by,'-'), count(distinct i.id), sum(" + LINE_NET + "), sum(" + LINE_PROFIT + ")"
                        + SALES_FROM + "group by 1 order by 3 desc",
                t("cashier"), n("bills"), m("sales"), m("profit"));
        add("payments", "sales", "REPORT_SALES", Range.TIMESTAMP, 2,
                "select m.name, count(*), sum(p.amount) from sales_payments p join sales_invoices i on i.id = p.invoice_id "
                        + "join payment_methods m on m.code = p.method "
                        + "where i.status = 'COMPLETED' and p.received_at >= ? and p.received_at < ? group by m.name order by 3 desc",
                t("method"), n("count"), m("amount"));
        add("gst_summary", "sales", "REPORT_FINANCIAL", Range.TIMESTAMP, 2,
                "select s.tax_rate, sum(s.taxable_value - s.returned_taxable), sum(s.tax_amount - s.returned_tax), "
                        + "sum(" + LINE_NET + ")" + SALES_FROM + "group by s.tax_rate order by s.tax_rate",
                p("gst_rate"), m("taxable"), m("tax"), m("total"));
        add("returns", "sales", "REPORT_SALES", Range.TIMESTAMP, 2,
                "select (r.return_time at time zone 'Asia/Kolkata')::date, r.return_no, i.invoice_no, r.return_type, r.reason, "
                        + "r.total, r.refund_total, r.credit_total from sales_returns r join sales_invoices i on i.id = r.invoice_id "
                        + "where r.return_time >= ? and r.return_time < ? order by r.return_time desc",
                d("date"), t("return_no"), t("invoice"), t("type"), t("reason"), m("value"), m("refunded"), m("credit"));
        add("profit_by_product", "finance", "REPORT_FINANCIAL", Range.TIMESTAMP, 2,
                "select s.product_name, sum(s.quantity - s.returned_qty), sum(s.taxable_value - s.returned_taxable), "
                        + "sum(s.unit_cost * (s.quantity - s.returned_qty)), sum(" + LINE_PROFIT + "), "
                        + "case when sum(s.taxable_value - s.returned_taxable) = 0 then 0 else "
                        + "round(100 * sum(" + LINE_PROFIT + ") / sum(s.taxable_value - s.returned_taxable), 1) end"
                        + SALES_FROM + "group by s.product_name having sum(s.quantity - s.returned_qty) > 0 order by 5 desc",
                t("product"), n("units"), m("revenue"), m("cost"), m("profit"), p("margin"));
        add("customer_dues", "finance", "REPORT_FINANCIAL", Range.NONE, 0,
                "select c.name, coalesce(c.phone,''), count(*), sum(i.balance_due), min(i.sale_time at time zone 'Asia/Kolkata')::date "
                        + "from sales_invoices i join customers c on c.id = i.customer_id "
                        + "where i.status = 'COMPLETED' and i.balance_due > 0 group by c.name, c.phone order by 4 desc",
                t("customer"), t("phone"), n("bills"), m("due"), d("oldest"));
        add("supplier_dues", "finance", "REPORT_FINANCIAL", Range.NONE, 0,
                "select name, code, balance from (select s.name, s.supplier_code code, s.opening_balance "
                        + "+ coalesce((select sum(total - returned_total) from purchase_invoices x where x.supplier_id = s.id and x.status = 'RECEIVED'),0) "
                        + "- coalesce((select sum(amount) from supplier_payments y where y.supplier_id = s.id),0) balance from suppliers s) q "
                        + "where balance <> 0 order by balance desc",
                t("supplier"), t("code"), m("balance"));
        add("expenses", "finance", "REPORT_FINANCIAL", Range.DATE, 2,
                "select c.name, count(*), sum(e.amount) from expenses e join expense_categories c on c.id = e.category_id "
                        + "where e.expense_date >= ? and e.expense_date <= ? group by c.name order by 3 desc",
                t("category"), n("count"), m("amount"));
        add("purchases", "finance", "REPORT_FINANCIAL", Range.DATE, 2,
                "select p.purchase_date, p.purchase_no, s.name, p.supplier_invoice_no, p.total, p.returned_total, p.amount_paid "
                        + "from purchase_invoices p join suppliers s on s.id = p.supplier_id "
                        + "where p.status = 'RECEIVED' and p.purchase_date >= ? and p.purchase_date <= ? order by p.purchase_date desc",
                d("date"), t("purchase_no"), t("supplier"), t("supplier_invoice"), m("total"), m("returned"), m("paid"));
        add("stock_valuation", "stock", "REPORT_FINANCIAL", Range.NONE, 0,
                "select coalesce(c.name,'-'), sum(b.available_qty), sum(b.available_qty * b.avg_cost), "
                        + "sum(b.available_qty * coalesce(v.selling_price, p.selling_price)) "
                        + "from stock_balances b join product_variants v on v.id = b.variant_id "
                        + "join products p on p.id = v.product_id left join categories c on c.id = p.category_id "
                        + "group by 1 order by 3 desc",
                t("category"), n("units"), m("cost_value"), m("retail_value"));
        add("low_stock", "stock", "REPORT_STOCK", Range.NONE, 0,
                "select p.name, v.sku, coalesce(sz.name,''), coalesce(col.name,''), b.available_qty, coalesce(p.min_stock, "
                        + "(select setting_value::int from app_settings where setting_key = 'stock.low_threshold')) "
                        + "from stock_balances b join product_variants v on v.id = b.variant_id and v.active "
                        + "join products p on p.id = v.product_id and p.active "
                        + "left join sizes sz on sz.id = v.size_id left join colours col on col.id = v.colour_id "
                        + "where b.available_qty <= coalesce(p.min_stock, "
                        + "(select setting_value::int from app_settings where setting_key = 'stock.low_threshold')) "
                        + "order by b.available_qty, lower(p.name)",
                t("product"), t("sku"), t("size"), t("colour"), n("available"), n("minimum"));
        add("stock_movements", "stock", "REPORT_STOCK", Range.TIMESTAMP, 2,
                "select (m.created_at at time zone 'Asia/Kolkata')::date, m.movement_type, count(*), sum(m.available_delta), sum(m.damaged_delta) "
                        + "from stock_movements m where m.created_at >= ? and m.created_at < ? group by 1, 2 order by 1 desc, 2",
                d("date"), t("type"), n("count"), n("units"), n("damaged"));
    }

    public Map<String, Definition> definitions() {
        return DEFS;
    }

    public Optional<Definition> find(String key) {
        return Optional.ofNullable(DEFS.get(key));
    }

    public Result run(String key, LocalDate from, LocalDate to) {
        Definition def = find(key).orElseThrow(() -> new BusinessException("error.notfound", "Report"));
        if (from == null || to == null || to.isBefore(from)) {
            throw new BusinessException("report.error.range");
        }
        if (to.isAfter(from.plusYears(5))) {
            throw new BusinessException("report.error.range.long");
        }
        Object[] args = switch (def.range()) {
            case TIMESTAMP -> new Object[]{Timestamp.from(clock.startOfDay(from)), Timestamp.from(clock.endOfDayExclusive(to))};
            case DATE -> new Object[]{from, to};
            case NONE -> new Object[0];
        };
        List<List<Object>> rows = jdbc.query(def.sql(), (rs, i) -> {
            List<Object> r = new ArrayList<>(def.columns().size());
            for (int c = 0; c < def.columns().size(); c++) {
                Object v = rs.getObject(c + 1);
                if (v instanceof java.sql.Date dt) {
                    v = dt.toLocalDate();
                }
                r.add(v);
            }
            return r;
        }, args);
        return new Result(def, from, to, rows, totals(def, rows));
    }

    /** Footer row: sums MONEY and NUMBER columns; null where a sum makes no sense. */
    private static List<Object> totals(Definition def, List<List<Object>> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        List<Object> out = new ArrayList<>();
        boolean any = false;
        for (int c = 0; c < def.columns().size(); c++) {
            ColType type = def.columns().get(c).type();
            String key = def.columns().get(c).key();
            if ((type == ColType.MONEY || type == ColType.NUMBER) && !key.equals("minimum") && !key.equals("available")) {
                BigDecimal sum = BigDecimal.ZERO;
                for (List<Object> r : rows) {
                    Object v = r.get(c);
                    if (v instanceof Number num) {
                        sum = sum.add(v instanceof BigDecimal bd ? bd : BigDecimal.valueOf(num.doubleValue()));
                    }
                }
                out.add(sum);
                any = true;
            } else {
                out.add(null);
            }
        }
        return any ? out : null;
    }

    public byte[] csv(Result r, java.util.function.Function<String, String> label) {
        List<String> header = r.definition().columns().stream().map(c -> label.apply(c.key())).toList();
        List<List<Object>> rows = new ArrayList<>(r.rows());
        return CsvUtil.write(header, rows);
    }

    /** Profit & loss for the period, bottom line for the owner. */
    public record ProfitLoss(BigDecimal sales, BigDecimal taxable, BigDecimal cogs, BigDecimal grossProfit,
                             BigDecimal expenses, BigDecimal netProfit, BigDecimal tax, BigDecimal purchasesReceived) {
    }

    public ProfitLoss profitLoss(LocalDate from, LocalDate to) {
        Timestamp a = Timestamp.from(clock.startOfDay(from));
        Timestamp b = Timestamp.from(clock.endOfDayExclusive(to));
        Object[] x = jdbc.queryForObject("select coalesce(sum(" + LINE_NET + "),0), "
                        + "coalesce(sum(s.taxable_value - s.returned_taxable),0), "
                        + "coalesce(sum(s.unit_cost * (s.quantity - s.returned_qty)),0), "
                        + "coalesce(sum(s.tax_amount - s.returned_tax),0)" + SALES_FROM,
                (rs, n2) -> new Object[]{rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4)}, a, b);
        BigDecimal exp = jdbc.queryForObject("select coalesce(sum(amount),0) from expenses where expense_date between ? and ?",
                BigDecimal.class, from, to);
        BigDecimal pur = jdbc.queryForObject("select coalesce(sum(total - returned_total),0) from purchase_invoices "
                + "where status = 'RECEIVED' and purchase_date between ? and ?", BigDecimal.class, from, to);
        BigDecimal taxable = (BigDecimal) x[1];
        BigDecimal cogs = ((BigDecimal) x[2]).setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal gross = taxable.subtract(cogs);
        return new ProfitLoss((BigDecimal) x[0], taxable, cogs, gross, exp, gross.subtract(exp), (BigDecimal) x[3], pur);
    }
}
