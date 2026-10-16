package com.maheshclothes.report;

import com.maheshclothes.common.Money;
import com.maheshclothes.common.ShopClock;
import com.maheshclothes.expense.ExpenseService;
import com.maheshclothes.inventory.InventoryQueryRepository;
import com.maheshclothes.inventory.InventoryQueryRepository.StockSummary;
import com.maheshclothes.invoice.WhatsappService;
import com.maheshclothes.purchasing.PurchaseQueryRepository;
import com.maheshclothes.sales.SalesQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Numbers for the owner's dashboard. Definitions (used everywhere, so screens agree):
 *   Sales   = invoice line totals of COMPLETED invoices minus returns   (what customers really paid for)
 *   Profit  = taxable value minus returned taxable minus cost of the goods that stayed sold
 *             (cost is the snapshot frozen on each invoice line, so it never changes later)
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final JdbcTemplate jdbc;
    private final ShopClock clock;
    private final InventoryQueryRepository inventory;
    private final SalesQueryRepository sales;
    private final PurchaseQueryRepository purchases;
    private final ExpenseService expenses;
    private final WhatsappService whatsapp;

    public record Period(long bills, BigDecimal sales, BigDecimal profit, long units) {
        public BigDecimal average() {
            return bills == 0 ? Money.ZERO : sales.divide(BigDecimal.valueOf(bills), 2, RoundingMode.HALF_UP);
        }
    }

    public record DayPoint(LocalDate day, BigDecimal sales, BigDecimal profit, long bills) {
    }

    public record NameValue(String name, BigDecimal value, long qty) {
    }

    public record HourPoint(int hour, long bills) {
    }

    public record LowStock(String product, String sku, String size, String colour, int available) {
    }

    public record Data(Period today, Period yesterday, Period month, Period lastMonth, BigDecimal monthExpenses,
                       BigDecimal receivable, BigDecimal payable, StockSummary stock, List<DayPoint> trend,
                       List<NameValue> topProducts, List<NameValue> categories, List<NameValue> payments,
                       List<HourPoint> hours, List<LowStock> lowStock, List<SalesQueryRepository.InvoiceRow> recent,
                       Map<String, Long> whatsappCounts, int trendDays) {

        /** Percent change today vs yesterday, null when there is nothing to compare with. */
        public BigDecimal todayChange() {
            return change(today.sales(), yesterday.sales());
        }

        public BigDecimal monthChange() {
            return change(month.sales(), lastMonth.sales());
        }

        public BigDecimal netProfitMonth() {
            return month.profit().subtract(monthExpenses);
        }

        private static BigDecimal change(BigDecimal now, BigDecimal before) {
            if (before == null || before.signum() == 0) {
                return null;
            }
            return now.subtract(before).multiply(Money.HUNDRED).divide(before, 1, RoundingMode.HALF_UP);
        }
    }

    public Data load(int trendDays) {
        LocalDate today = clock.today();
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate lastMonthStart = monthStart.minusMonths(1);
        // "same days into last month" so the comparison is fair mid-month
        LocalDate lastMonthSame = lastMonthStart.plusDays(Math.min(today.getDayOfMonth(),
                lastMonthStart.lengthOfMonth()) - 1);
        int days = Math.max(7, Math.min(trendDays, 90));

        Period t = period(today, today);
        Period y = period(today.minusDays(1), today.minusDays(1));
        Period m = period(monthStart, today);
        Period lm = period(lastMonthStart, lastMonthSame);
        return new Data(t, y, m, lm, expenses.total(monthStart, today), sales.totalReceivable(), purchases.totalPayable(),
                inventory.summary(), trend(today.minusDays(days - 1L), today), topProducts(today.minusDays(29), today, 6),
                categories(today.minusDays(29), today), payments(today.minusDays(29), today),
                hours(today.minusDays(29), today), lowStock(8), recent(8), whatsapp.statusCounts(), days);
    }

    // ---- building blocks: all take LOCAL dates and convert to UTC instants for the index on sale_time ----

    private Timestamp start(LocalDate d) {
        return Timestamp.from(clock.startOfDay(d));
    }

    private Timestamp endExclusive(LocalDate d) {
        return Timestamp.from(clock.endOfDayExclusive(d));
    }

    public Period period(LocalDate from, LocalDate to) {
        return jdbc.queryForObject("select count(distinct i.id), coalesce(sum(s.line_total - s.returned_total),0), "
                        + "coalesce(sum(s.taxable_value - s.returned_taxable - s.unit_cost * (s.quantity - s.returned_qty)),0), "
                        + "coalesce(sum(s.quantity - s.returned_qty),0) "
                        + "from sales_invoices i join sales_items s on s.invoice_id = i.id "
                        + "where i.status = 'COMPLETED' and i.sale_time >= ? and i.sale_time < ?",
                (rs, n) -> new Period(rs.getLong(1), rs.getBigDecimal(2), rs.getBigDecimal(3).setScale(2, RoundingMode.HALF_UP),
                        rs.getLong(4)), start(from), endExclusive(to));
    }

    /** One point per day, with zero-filled gaps so the chart axis is continuous. */
    public List<DayPoint> trend(LocalDate from, LocalDate to) {
        Map<LocalDate, DayPoint> found = new java.util.HashMap<>();
        jdbc.query("select (i.sale_time at time zone ?)::date d, count(distinct i.id), sum(s.line_total - s.returned_total), "
                        + "sum(s.taxable_value - s.returned_taxable - s.unit_cost * (s.quantity - s.returned_qty)) "
                        + "from sales_invoices i join sales_items s on s.invoice_id = i.id "
                        + "where i.status = 'COMPLETED' and i.sale_time >= ? and i.sale_time < ? group by 1",
                rs -> {
                    LocalDate d = rs.getDate(1).toLocalDate();
                    found.put(d, new DayPoint(d, rs.getBigDecimal(3), rs.getBigDecimal(4).setScale(2, RoundingMode.HALF_UP),
                            rs.getLong(2)));
                }, clock.zone().getId(), start(from), endExclusive(to));
        List<DayPoint> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            out.add(found.getOrDefault(d, new DayPoint(d, Money.ZERO, Money.ZERO, 0)));
        }
        return out;
    }

    public List<NameValue> topProducts(LocalDate from, LocalDate to, int limit) {
        return jdbc.query("select s.product_name, sum(s.line_total - s.returned_total), sum(s.quantity - s.returned_qty) "
                        + "from sales_items s join sales_invoices i on i.id = s.invoice_id "
                        + "where i.status = 'COMPLETED' and i.sale_time >= ? and i.sale_time < ? "
                        + "group by s.product_name having sum(s.quantity - s.returned_qty) > 0 "
                        + "order by 3 desc, 2 desc limit ?",
                (rs, n) -> new NameValue(rs.getString(1), rs.getBigDecimal(2), rs.getLong(3)),
                start(from), endExclusive(to), limit);
    }

    public List<NameValue> categories(LocalDate from, LocalDate to) {
        return jdbc.query("select coalesce(c.name, '-'), sum(s.line_total - s.returned_total), sum(s.quantity - s.returned_qty) "
                        + "from sales_items s join sales_invoices i on i.id = s.invoice_id "
                        + "join product_variants v on v.id = s.variant_id join products p on p.id = v.product_id "
                        + "left join categories c on c.id = p.category_id "
                        + "where i.status = 'COMPLETED' and i.sale_time >= ? and i.sale_time < ? "
                        + "group by 1 having sum(s.line_total - s.returned_total) > 0 order by 2 desc limit 8",
                (rs, n) -> new NameValue(rs.getString(1), rs.getBigDecimal(2), rs.getLong(3)),
                start(from), endExclusive(to));
    }

    public List<NameValue> payments(LocalDate from, LocalDate to) {
        return jdbc.query("select m.name, sum(p.amount), count(*) from sales_payments p "
                        + "join sales_invoices i on i.id = p.invoice_id join payment_methods m on m.code = p.method "
                        + "where i.status = 'COMPLETED' and p.received_at >= ? and p.received_at < ? "
                        + "group by m.name order by 2 desc",
                (rs, n) -> new NameValue(rs.getString(1), rs.getBigDecimal(2), rs.getLong(3)),
                start(from), endExclusive(to));
    }

    /** Bills per hour of day (shop time): shows the owner when the shop is busy. */
    public List<HourPoint> hours(LocalDate from, LocalDate to) {
        long[] byHour = new long[24];
        jdbc.query("select extract(hour from i.sale_time at time zone ?)::int, count(*) from sales_invoices i "
                        + "where i.status = 'COMPLETED' and i.sale_time >= ? and i.sale_time < ? group by 1",
                rs -> {
                    byHour[rs.getInt(1)] = rs.getLong(2);
                }, clock.zone().getId(), start(from), endExclusive(to));
        List<HourPoint> out = new ArrayList<>();
        for (int h = 8; h <= 22; h++) {          // a shop day; busy hours outside this range are folded away
            out.add(new HourPoint(h, byHour[h]));
        }
        return out;
    }

    public List<LowStock> lowStock(int limit) {
        int threshold = inventory.lowThresholdValue();
        return jdbc.query("select p.name, v.sku, sz.name, col.name, b.available_qty from stock_balances b "
                        + "join product_variants v on v.id = b.variant_id and v.active "
                        + "join products p on p.id = v.product_id and p.active "
                        + "left join sizes sz on sz.id = v.size_id left join colours col on col.id = v.colour_id "
                        + "where b.available_qty <= coalesce(p.min_stock, ?) "
                        + "order by b.available_qty, lower(p.name) limit ?",
                (rs, n) -> new LowStock(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5)),
                threshold, limit);
    }

    public List<SalesQueryRepository.InvoiceRow> recent(int limit) {
        Timestamp from = Timestamp.from(clock.startOfDay(clock.today().minusDays(60)));
        return sales.invoices(new SalesQueryRepository.InvoiceFilter(from.toInstant(),
                clock.endOfDayExclusive(clock.today()), null, null, null, null, false), 0, limit).items();
    }
}
