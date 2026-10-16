package com.maheshclothes.sales;

import com.maheshclothes.catalog.ProductQueryRepository;
import com.maheshclothes.catalog.ProductQueryRepository.VariantInfo;
import com.maheshclothes.common.AuditService;
import com.maheshclothes.common.BusinessException;
import com.maheshclothes.common.Money;
import com.maheshclothes.common.NotFoundException;
import com.maheshclothes.common.NumberingService;
import com.maheshclothes.common.SettingsService;
import com.maheshclothes.common.ShopClock;
import com.maheshclothes.inventory.MovementType;
import com.maheshclothes.inventory.StockBalance;
import com.maheshclothes.inventory.StockMovement;
import com.maheshclothes.inventory.StockService;
import com.maheshclothes.sales.SalesRepositories.CustomerRepository;
import com.maheshclothes.sales.SalesRepositories.PaymentMethodRepository;
import com.maheshclothes.sales.SalesRepositories.SalesInvoiceRepository;
import com.maheshclothes.sales.SalesRepositories.SalesItemRepository;
import com.maheshclothes.sales.SalesRepositories.SalesPaymentRepository;
import com.maheshclothes.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Billing. One transaction does everything: price the cart, lock and reduce stock, freeze costs,
 * record payments, and queue the WhatsApp invoice. Retrying with the same client_request_id returns the
 * invoice that was already created, so a double-click or flaky network can never bill twice.
 */
@Service
@RequiredArgsConstructor
public class SalesService {

    private final SalesInvoiceRepository invoices;
    private final SalesItemRepository items;
    private final SalesPaymentRepository payments;
    private final PaymentMethodRepository methods;
    private final CustomerRepository customers;
    private final CustomerService customerService;
    private final ProductQueryRepository productQuery;
    private final StockService stock;
    private final NumberingService numbering;
    private final SettingsService settings;
    private final ShopClock clock;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    public record CartLine(UUID variantId, int quantity, BigDecimal lineDiscount) {
    }

    public record PaymentInput(String method, BigDecimal amount, String reference) {
    }

    public record SaleRequest(UUID clientRequestId, UUID customerId, String walkInName, String walkInPhone,
                              List<CartLine> lines, BigDecimal invoiceDiscount, List<PaymentInput> payments,
                              UUID exchangeReturnId, BigDecimal exchangeCredit, String notes, boolean sendWhatsApp) {
    }

    public SalesInvoice get(UUID id) {
        return invoices.findById(id).orElseThrow(() -> new NotFoundException("Invoice"));
    }

    /** Prices a cart exactly like checkout does, without touching stock. The billing screen shows this total. */
    public PricingCalculator.Result quote(List<CartLine> lines, BigDecimal invoiceDiscount) {
        if (lines == null || lines.isEmpty()) {
            throw new BusinessException("sale.error.empty");
        }
        Map<UUID, VariantInfo> info = productQuery.variantInfo(lines.stream().map(CartLine::variantId).collect(Collectors.toSet()));
        boolean taxOn = settings.bool("tax.enabled");
        List<PricingCalculator.LineInput> inputs = new ArrayList<>();
        for (CartLine l : lines) {
            VariantInfo vi = info.get(l.variantId());
            if (vi == null) {
                throw new BusinessException("error.notfound", "Item");
            }
            inputs.add(new PricingCalculator.LineInput(vi.price(), l.quantity(), l.lineDiscount(),
                    taxOn ? vi.taxRate() : BigDecimal.ZERO));
        }
        PricingCalculator.Result calc = PricingCalculator.compute(inputs, invoiceDiscount,
                settings.bool("tax.prices_include_tax"), settings.bool("invoice.round_off"));
        checkDiscountLimit(calc);
        return calc;
    }

    @Transactional
    public SalesInvoice createSale(SaleRequest r) {
        if (r.clientRequestId() == null) {
            throw new BusinessException("sale.error.request");
        }
        var existing = invoices.findByClientRequestId(r.clientRequestId());
        if (existing.isPresent()) {
            return existing.get();
        }
        if (r.lines() == null || r.lines().isEmpty()) {
            throw new BusinessException("sale.error.empty");
        }
        Set<UUID> variantIds = new HashSet<>();
        for (CartLine l : r.lines()) {
            if (!variantIds.add(l.variantId())) {
                throw new BusinessException("sale.error.duplicate.line");
            }
            if (l.quantity() <= 0) {
                throw new BusinessException("sale.error.quantity");
            }
        }
        Map<UUID, VariantInfo> info = productQuery.variantInfo(variantIds);
        if (info.size() != variantIds.size()) {
            throw new BusinessException("error.notfound", "Item");
        }
        for (VariantInfo vi : info.values()) {
            if (!vi.active()) {
                throw new BusinessException("sale.error.inactive", vi.label());
            }
        }

        boolean taxOn = settings.bool("tax.enabled");
        boolean inclusive = settings.bool("tax.prices_include_tax");
        boolean roundOff = settings.bool("invoice.round_off");

        List<PricingCalculator.LineInput> inputs = new ArrayList<>();
        for (CartLine l : r.lines()) {
            VariantInfo vi = info.get(l.variantId());
            inputs.add(new PricingCalculator.LineInput(vi.price(), l.quantity(), l.lineDiscount(),
                    taxOn ? vi.taxRate() : BigDecimal.ZERO));
        }
        PricingCalculator.Result calc = PricingCalculator.compute(inputs, r.invoiceDiscount(), inclusive, roundOff);
        checkDiscountLimit(calc);

        // ---- payments ----
        BigDecimal paid = Money.ZERO;
        List<PaymentInput> cleaned = new ArrayList<>();
        if (r.payments() != null) {
            for (PaymentInput p : r.payments()) {
                BigDecimal amt = Money.scale(p.amount());
                if (amt.signum() == 0) {
                    continue;
                }
                if (amt.signum() < 0) {
                    throw new BusinessException("payment.error.amount");
                }
                PaymentMethod pm = methods.findById(p.method() == null ? "" : p.method())
                        .orElseThrow(() -> new BusinessException("payment.error.method"));
                if (!pm.isActive() || !pm.isSelectable()) {
                    throw new BusinessException("payment.error.method");
                }
                if (pm.isRequiresReference() && (p.reference() == null || p.reference().isBlank())) {
                    throw new BusinessException("payment.error.reference", pm.getName());
                }
                cleaned.add(new PaymentInput(pm.getCode(), amt, p.reference() == null ? null : p.reference().trim()));
                paid = paid.add(amt);
            }
        }
        BigDecimal credit = Money.scale(r.exchangeCredit());
        if (credit.signum() > 0) {
            if (r.exchangeReturnId() == null) {
                throw new BusinessException("sale.error.credit");
            }
            paid = paid.add(credit);
        }
        if (paid.compareTo(calc.grandTotal()) > 0) {
            throw new BusinessException("sale.error.overpay");
        }
        BigDecimal balance = calc.grandTotal().subtract(paid);

        // ---- customer ----
        Customer customer = null;
        if (r.customerId() != null) {
            customer = customers.lockById(r.customerId()).orElseThrow(() -> new NotFoundException("Customer"));
            if (!customer.isActive()) {
                throw new BusinessException("customer.error.inactive");
            }
        }
        if (balance.signum() > 0) {
            if (!CurrentUser.has("CREDIT_SALE") || !settings.bool("credit.enabled")) {
                throw new BusinessException("sale.error.credit.notallowed");
            }
            if (customer == null) {
                throw new BusinessException("sale.error.credit.customer");
            }
            if (customer.getCreditLimit() != null) {
                BigDecimal after = customerService.outstanding(customer.getId()).add(balance);
                if (after.compareTo(customer.getCreditLimit()) > 0) {
                    throw new BusinessException("sale.error.credit.limit", Money.scale(customer.getCreditLimit()).toPlainString());
                }
            }
        }

        // ---- stock (locked in id order inside StockService.lock) ----
        Map<UUID, StockBalance> balances = stock.lock(variantIds);
        for (CartLine l : r.lines()) {
            StockBalance b = balances.get(l.variantId());
            if (b == null || b.getAvailableQty() < l.quantity()) {
                throw new BusinessException("sale.error.stock", info.get(l.variantId()).label(),
                        b == null ? 0 : b.getAvailableQty());
            }
        }

        // ---- invoice ----
        Instant now = clock.now();
        SalesInvoice inv = new SalesInvoice();
        inv.setInvoiceNo(numbering.nextInvoiceNo(clock.toLocalDate(now)));
        inv.setSaleTime(now);
        inv.setClientRequestId(r.clientRequestId());
        if (customer != null) {
            inv.setCustomerId(customer.getId());
            inv.setCustomerName(customer.getName());
            inv.setCustomerPhone(customer.getPhone());
        } else {
            inv.setCustomerName(blank(r.walkInName()));
            inv.setCustomerPhone(blank(r.walkInPhone()));
        }
        inv.setPricesIncludeTax(inclusive);
        inv.setSubtotal(calc.subtotal());
        inv.setLineDiscountTotal(calc.lineDiscountTotal());
        inv.setInvoiceDiscount(calc.invoiceDiscount());
        inv.setTaxableTotal(calc.taxableTotal());
        inv.setTaxTotal(calc.taxTotal());
        inv.setRoundOff(calc.roundOff());
        inv.setTotal(calc.grandTotal());
        inv.setAmountPaid(paid);
        inv.setBalanceDue(balance);
        inv.setExchangeReturnId(credit.signum() > 0 ? r.exchangeReturnId() : null);
        inv.setNotes(blank(r.notes()));
        inv = invoices.save(inv);

        StockService.Ref ref = new StockService.Ref("SALE", inv.getId(), inv.getInvoiceNo());
        BigDecimal totalCost = BigDecimal.ZERO;
        List<SalesItem> rows = new ArrayList<>();
        for (int i = 0; i < r.lines().size(); i++) {
            CartLine l = r.lines().get(i);
            VariantInfo vi = info.get(l.variantId());
            PricingCalculator.LineResult lr = calc.lines().get(i);
            StockMovement m = stock.issue(balances.get(l.variantId()), l.quantity(), MovementType.SALE, ref, null);
            SalesItem it = new SalesItem();
            it.setInvoiceId(inv.getId());
            it.setVariantId(l.variantId());
            it.setProductName(vi.productName());
            it.setSku(vi.sku());
            it.setSizeName(vi.sizeName());
            it.setColourName(vi.colourName());
            it.setHsnCode(vi.hsnCode());
            it.setQuantity(l.quantity());
            it.setUnitPrice(Money.scale(vi.price()));
            it.setLineDiscount(lr.lineDiscount());
            it.setInvoiceDiscountShare(lr.invoiceDiscountShare());
            it.setTaxableValue(lr.taxableValue());
            it.setTaxRate(lr.taxRate());
            it.setTaxAmount(lr.taxAmount());
            it.setLineTotal(lr.lineTotal());
            it.setUnitCost(m.getUnitCost());
            rows.add(it);
            totalCost = totalCost.add(m.getUnitCost().multiply(BigDecimal.valueOf(l.quantity())));
        }
        items.saveAll(rows);
        inv.setTotalCost(totalCost.setScale(2, RoundingMode.HALF_UP));

        for (PaymentInput p : cleaned) {
            SalesPayment sp = new SalesPayment();
            sp.setInvoiceId(inv.getId());
            sp.setPaymentKind("SALE");
            sp.setMethod(p.method());
            sp.setAmount(p.amount());
            sp.setReference(p.reference());
            sp.setReceivedAt(now);
            payments.save(sp);
        }
        if (credit.signum() > 0) {
            useExchangeCredit(r.exchangeReturnId(), credit);
            SalesPayment sp = new SalesPayment();
            sp.setInvoiceId(inv.getId());
            sp.setPaymentKind("EXCHANGE_CREDIT");
            sp.setMethod("EXCHANGE_CREDIT");
            sp.setAmount(credit);
            sp.setReceivedAt(now);
            payments.save(sp);
        }
        inv = invoices.save(inv);
        audit.log("SALE_CREATED", "invoice", inv.getId(), inv.getInvoiceNo() + " total " + inv.getTotal()
                + (balance.signum() > 0 ? " due " + balance : ""));
        events.publishEvent(new SaleCompletedEvent(inv.getId(), inv.getInvoiceNo(), inv.getCustomerId(),
                inv.getCustomerName(), inv.getCustomerPhone(), r.sendWhatsApp()));
        return inv;
    }

    /** Collects money against an invoice's unpaid balance (credit customers paying later). */
    @Transactional
    public SalesPayment receiveBalance(UUID invoiceId, String method, BigDecimal amount, String reference) {
        BigDecimal amt = Money.scale(amount);
        if (amt.signum() <= 0) {
            throw new BusinessException("payment.error.amount");
        }
        SalesInvoice inv = invoices.lockById(invoiceId).orElseThrow(() -> new NotFoundException("Invoice"));
        if (inv.getStatus() != SaleStatus.COMPLETED) {
            throw new BusinessException("sale.error.cancelled");
        }
        if (amt.compareTo(inv.getBalanceDue()) > 0) {
            throw new BusinessException("sale.error.overpay");
        }
        PaymentMethod pm = methods.findById(method == null ? "" : method)
                .orElseThrow(() -> new BusinessException("payment.error.method"));
        if (!pm.isActive() || !pm.isSelectable()) {
            throw new BusinessException("payment.error.method");
        }
        if (pm.isRequiresReference() && (reference == null || reference.isBlank())) {
            throw new BusinessException("payment.error.reference", pm.getName());
        }
        SalesPayment sp = new SalesPayment();
        sp.setInvoiceId(invoiceId);
        sp.setPaymentKind("BALANCE");
        sp.setMethod(pm.getCode());
        sp.setAmount(amt);
        sp.setReference(blank(reference));
        sp = payments.save(sp);
        inv.setAmountPaid(inv.getAmountPaid().add(amt));
        inv.setBalanceDue(inv.getBalanceDue().subtract(amt));
        invoices.save(inv);
        audit.log("SALE_BALANCE_PAYMENT", "invoice", invoiceId, inv.getInvoiceNo() + " " + amt + " " + pm.getCode());
        return sp;
    }

    /**
     * Cancels a completed invoice that has had no returns: stock goes back at the original cost, and
     * money already collected becomes a cancellation refund. Needs a reason.
     */
    @Transactional
    public SalesInvoice cancel(UUID invoiceId, String reason, String refundMethod) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("sale.error.cancel.reason");
        }
        SalesInvoice inv = invoices.lockById(invoiceId).orElseThrow(() -> new NotFoundException("Invoice"));
        if (inv.getStatus() != SaleStatus.COMPLETED) {
            throw new BusinessException("sale.error.cancelled");
        }
        List<SalesItem> rows = items.lockByInvoiceId(invoiceId);
        if (rows.stream().anyMatch(i -> i.getReturnedQty() > 0)) {
            throw new BusinessException("sale.error.cancel.hasreturns");
        }
        if (inv.getExchangeReturnId() != null) {
            throw new BusinessException("sale.error.cancel.exchange");
        }
        Map<UUID, StockBalance> balances = stock.lock(rows.stream().map(SalesItem::getVariantId).collect(Collectors.toSet()));
        StockService.Ref ref = new StockService.Ref("SALE", inv.getId(), inv.getInvoiceNo());
        for (SalesItem it : rows) {
            stock.receive(balances.get(it.getVariantId()), it.getQuantity(), it.getUnitCost(),
                    MovementType.SALE_CANCEL, ref, reason.trim());
        }
        BigDecimal refund = inv.getAmountPaid();
        if (refund.signum() > 0) {
            PaymentMethod pm = methods.findById(refundMethod == null ? "CASH" : refundMethod)
                    .orElseThrow(() -> new BusinessException("payment.error.method"));
            jdbc.update("insert into refunds (refund_no, refund_type, invoice_id, amount, method, refunded_at) "
                            + "values (?, 'CANCELLATION', ?, ?, ?, now())",
                    numbering.nextDocument("REFUND", "REF", clock.today()), invoiceId, refund, pm.getCode());
        }
        inv.setStatus(SaleStatus.CANCELLED);
        inv.setCancelledAt(clock.now());
        inv.setCancelledBy(CurrentUser.username());
        inv.setCancelReason(reason.trim());
        audit.log("SALE_CANCELLED", "invoice", invoiceId, inv.getInvoiceNo() + ": " + reason.trim());
        return invoices.save(inv);
    }

    private void useExchangeCredit(UUID returnId, BigDecimal amount) {
        int n = jdbc.update("update sales_returns set credit_used = credit_used + ?, "
                + "credit_status = case when credit_used + ? >= credit_total then 'USED' else 'PENDING' end "
                + "where id = ? and credit_status in ('PENDING') and credit_used + ? <= credit_total",
                amount, amount, returnId, amount);
        if (n != 1) {
            throw new BusinessException("sale.error.credit.invalid");
        }
    }

    private void checkDiscountLimit(PricingCalculator.Result calc) {
        if (CurrentUser.has("DISCOUNT_UNLIMITED") || calc.subtotal().signum() == 0) {
            return;
        }
        BigDecimal disc = calc.lineDiscountTotal().add(calc.invoiceDiscount());
        BigDecimal max = settings.decimal("discount.cashier_max_percent", new BigDecimal("10"));
        BigDecimal pct = disc.multiply(Money.HUNDRED).divide(calc.subtotal(), 2, RoundingMode.HALF_UP);
        if (pct.compareTo(max) > 0) {
            throw new BusinessException("sale.error.discount.limit", max.stripTrailingZeros().toPlainString());
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
