package com.maheshclothes.salesreturn;

import com.maheshclothes.common.AuditService;
import com.maheshclothes.common.BusinessException;
import com.maheshclothes.common.Money;
import com.maheshclothes.common.NotFoundException;
import com.maheshclothes.common.NumberingService;
import com.maheshclothes.common.Proration;
import com.maheshclothes.common.SettingsService;
import com.maheshclothes.common.ShopClock;
import com.maheshclothes.inventory.MovementType;
import com.maheshclothes.inventory.StockBalance;
import com.maheshclothes.inventory.StockService;
import com.maheshclothes.sales.PaymentMethod;
import com.maheshclothes.sales.SaleStatus;
import com.maheshclothes.sales.SalesInvoice;
import com.maheshclothes.sales.SalesItem;
import com.maheshclothes.sales.SalesRepositories.PaymentMethodRepository;
import com.maheshclothes.sales.SalesRepositories.SalesInvoiceRepository;
import com.maheshclothes.sales.SalesRepositories.SalesItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Returns, exchanges and refunds. Money rules, in order:
 *  1. the returned value first reduces what the customer still owes on that invoice;
 *  2. whatever is left is refunded (RETURN) or kept as exchange credit (EXCHANGE);
 *  3. when the last units of an invoice come back, the rupee round-off is returned too, so the customer
 *     gets back exactly what they paid.
 * Refunds can never exceed what the customer actually paid.
 */
@Service
@RequiredArgsConstructor
public class ReturnService {

    private final SalesInvoiceRepository invoices;
    private final SalesItemRepository items;
    private final PaymentMethodRepository methods;
    private final StockService stock;
    private final NumberingService numbering;
    private final SettingsService settings;
    private final ShopClock clock;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    public enum Condition { RESELLABLE, DAMAGED }

    public record ReturnLine(UUID salesItemId, int quantity, Condition condition) {
    }

    public record ReturnRequest(UUID clientRequestId, UUID invoiceId, boolean exchange, String reason,
                                List<ReturnLine> lines, String refundMethod, String refundReference) {
    }

    public record ReturnResult(UUID returnId, String returnNo, BigDecimal total, BigDecimal balanceAdjusted,
                               BigDecimal refunded, BigDecimal credit) {
    }

    @Transactional
    public ReturnResult process(ReturnRequest r) {
        if (r.clientRequestId() == null) {
            throw new BusinessException("sale.error.request");
        }
        List<ReturnResult> prior = jdbc.query("select id, return_no, total, balance_adjusted, refund_total, credit_total "
                        + "from sales_returns where client_request_id = ?",
                (rs, n) -> new ReturnResult(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getBigDecimal(3),
                        rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6)), r.clientRequestId());
        if (!prior.isEmpty()) {
            return prior.get(0);
        }
        if (r.reason() == null || r.reason().isBlank()) {
            throw new BusinessException("return.error.reason");
        }
        if (r.lines() == null || r.lines().stream().noneMatch(l -> l.quantity() > 0)) {
            throw new BusinessException("return.error.noitems");
        }
        SalesInvoice inv = invoices.lockById(r.invoiceId()).orElseThrow(() -> new NotFoundException("Invoice"));
        if (inv.getStatus() != SaleStatus.COMPLETED) {
            throw new BusinessException("sale.error.cancelled");
        }
        String window = settings.get("return.window_days", "");
        if (!window.isBlank()) {
            try {
                long days = Long.parseLong(window.trim());
                if (Duration.between(inv.getSaleTime(), Instant.now()).toDays() > days) {
                    throw new BusinessException("return.error.window", days);
                }
            } catch (NumberFormatException ignored) {
                // an unreadable setting means "no window"
            }
        }

        List<SalesItem> all = items.lockByInvoiceId(inv.getId());
        Map<UUID, SalesItem> byId = all.stream().collect(Collectors.toMap(SalesItem::getId, i -> i));
        Map<UUID, StockBalance> balances = stock.lock(r.lines().stream().filter(l -> l.quantity() > 0)
                .map(l -> byId.get(l.salesItemId()))
                .peek(i -> {
                    if (i == null) {
                        throw new NotFoundException("Invoice line");
                    }
                })
                .map(SalesItem::getVariantId).collect(Collectors.toSet()));

        UUID returnId = UUID.randomUUID();
        String returnNo = numbering.nextDocument("RETURN", "RET", clock.today());
        StockService.Ref ref = new StockService.Ref("RETURN", returnId, returnNo);

        BigDecimal taxable = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        List<Object[]> itemRows = new ArrayList<>();
        for (ReturnLine l : r.lines()) {
            if (l.quantity() <= 0) {
                continue;
            }
            SalesItem it = byId.get(l.salesItemId());
            if (l.quantity() > it.getQuantity() - it.getReturnedQty()) {
                throw new BusinessException("return.error.quantity");
            }
            BigDecimal amount = Proration.portion(it.getLineTotal(), it.getQuantity(), it.getReturnedQty(),
                    it.getReturnedTotal(), l.quantity());
            BigDecimal taxPart = Proration.portion(it.getTaxAmount(), it.getQuantity(), it.getReturnedQty(),
                    it.getReturnedTax(), l.quantity());
            BigDecimal taxablePart = amount.subtract(taxPart);
            Condition cond = l.condition() == null ? Condition.RESELLABLE : l.condition();
            StockBalance b = balances.get(it.getVariantId());
            if (cond == Condition.RESELLABLE) {
                stock.receive(b, l.quantity(), it.getUnitCost(), MovementType.SALE_RETURN, ref, r.reason().trim());
            } else {
                stock.receiveDamaged(b, l.quantity(), it.getUnitCost(), MovementType.RETURN_DAMAGED, ref, r.reason().trim());
            }
            it.setReturnedQty(it.getReturnedQty() + l.quantity());
            it.setReturnedTotal(it.getReturnedTotal().add(amount));
            it.setReturnedTax(it.getReturnedTax().add(taxPart));
            it.setReturnedTaxable(it.getReturnedTaxable().add(taxablePart));
            itemRows.add(new Object[]{returnId, it.getId(), it.getVariantId(), l.quantity(), taxablePart, taxPart, amount,
                    it.getUnitCost(), cond.name()});
            taxable = taxable.add(taxablePart);
            tax = tax.add(taxPart);
            total = total.add(amount);
        }
        items.saveAll(all);

        BigDecimal roundOffAdjust = Money.ZERO;
        if (all.stream().allMatch(i -> i.getReturnedQty() == i.getQuantity())) {
            roundOffAdjust = Money.scale(inv.getRoundOff());
            total = total.add(roundOffAdjust);
        }
        total = Money.scale(total);

        BigDecimal due = inv.getBalanceDue();
        BigDecimal adjusted = total.min(due);
        BigDecimal remaining = total.subtract(adjusted);
        BigDecimal alreadyOut = jdbc.queryForObject("select coalesce(sum(refund_total + credit_total),0) from sales_returns "
                + "where invoice_id = ?", BigDecimal.class, inv.getId());
        if (remaining.signum() > 0 && alreadyOut.add(remaining).compareTo(inv.getAmountPaid()) > 0) {
            throw new BusinessException("return.error.overrefund");
        }

        BigDecimal refund = Money.ZERO;
        BigDecimal credit = Money.ZERO;
        PaymentMethod pm = null;
        if (r.exchange()) {
            credit = remaining;
        } else if (remaining.signum() > 0) {
            refund = remaining;
            pm = methods.findById(r.refundMethod() == null ? "CASH" : r.refundMethod())
                    .orElseThrow(() -> new BusinessException("payment.error.method"));
            if (!pm.isActive() || !pm.isSelectable()) {
                throw new BusinessException("payment.error.method");
            }
            if (pm.isRequiresReference() && (r.refundReference() == null || r.refundReference().isBlank())) {
                throw new BusinessException("payment.error.reference", pm.getName());
            }
        }

        jdbc.update("insert into sales_returns (id, return_no, invoice_id, return_time, return_type, reason, taxable_total, "
                        + "tax_total, round_off_adjust, total, balance_adjusted, refund_total, credit_total, credit_status, "
                        + "client_request_id, created_by) values (?,?,?,now(),?,?,?,?,?,?,?,?,?,?,?,?)",
                returnId, returnNo, inv.getId(), r.exchange() ? "EXCHANGE" : "RETURN", r.reason().trim(), taxable, tax,
                roundOffAdjust, total, adjusted, refund, credit, credit.signum() > 0 ? "PENDING" : "NONE",
                r.clientRequestId(), com.maheshclothes.security.CurrentUser.username());
        for (Object[] row : itemRows) {
            jdbc.update("insert into sales_return_items (return_id, sales_item_id, variant_id, quantity, taxable_value, "
                    + "tax_amount, amount, unit_cost, item_condition) values (?,?,?,?,?,?,?,?,?)", row);
        }
        if (refund.signum() > 0) {
            jdbc.update("insert into refunds (refund_no, refund_type, invoice_id, return_id, amount, method, reference) "
                            + "values (?, 'RETURN', ?, ?, ?, ?, ?)",
                    numbering.nextDocument("REFUND", "REF", clock.today()), inv.getId(), returnId, refund, pm.getCode(),
                    r.refundReference() == null || r.refundReference().isBlank() ? null : r.refundReference().trim());
        }
        if (adjusted.signum() > 0) {
            inv.setBalanceDue(inv.getBalanceDue().subtract(adjusted));
            invoices.save(inv);
        }
        audit.log(r.exchange() ? "EXCHANGE_RETURN" : "SALE_RETURN", "invoice", inv.getId(),
                returnNo + " total " + total + " refund " + refund + " credit " + credit);
        return new ReturnResult(returnId, returnNo, total, adjusted, refund, credit);
    }

    /** Pays out unused exchange credit in cash/UPI instead of a replacement sale. */
    @Transactional
    public BigDecimal refundCredit(UUID returnId, String method, String reference) {
        List<Object[]> rows = jdbc.query("select invoice_id, credit_total, credit_used, credit_status from sales_returns "
                        + "where id = ? for update",
                (rs, n) -> new Object[]{UUID.fromString(rs.getString(1)), rs.getBigDecimal(2), rs.getBigDecimal(3),
                        rs.getString(4)}, returnId);
        if (rows.isEmpty()) {
            throw new NotFoundException("Return");
        }
        Object[] row = rows.get(0);
        BigDecimal left = ((BigDecimal) row[1]).subtract((BigDecimal) row[2]);
        if (!"PENDING".equals(row[3]) || left.signum() <= 0) {
            throw new BusinessException("return.error.credit.none");
        }
        PaymentMethod pm = methods.findById(method == null ? "CASH" : method)
                .orElseThrow(() -> new BusinessException("payment.error.method"));
        if (!pm.isActive() || !pm.isSelectable()) {
            throw new BusinessException("payment.error.method");
        }
        if (pm.isRequiresReference() && (reference == null || reference.isBlank())) {
            throw new BusinessException("payment.error.reference", pm.getName());
        }
        jdbc.update("insert into refunds (refund_no, refund_type, invoice_id, return_id, amount, method, reference) "
                        + "values (?, 'EXCHANGE_BALANCE', ?, ?, ?, ?, ?)",
                numbering.nextDocument("REFUND", "REF", clock.today()), row[0], returnId, left, pm.getCode(),
                reference == null || reference.isBlank() ? null : reference.trim());
        // credit_total already counts this money in the over-refund guard, so only the status changes
        jdbc.update("update sales_returns set credit_status = 'REFUNDED' where id = ?", returnId);
        audit.log("EXCHANGE_CREDIT_REFUNDED", "return", returnId, "refund " + left);
        return left;
    }
}
