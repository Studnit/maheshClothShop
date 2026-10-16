package com.maheshclothes.purchasing;

import com.maheshclothes.catalog.Product;
import com.maheshclothes.catalog.ProductQueryRepository;
import com.maheshclothes.catalog.ProductQueryRepository.VariantInfo;
import com.maheshclothes.catalog.ProductRepository;
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
import com.maheshclothes.inventory.StockMovement;
import com.maheshclothes.inventory.StockService;
import com.maheshclothes.purchasing.PurchaseRepositories.PurchaseInvoiceRepository;
import com.maheshclothes.purchasing.PurchaseRepositories.PurchaseItemRepository;
import com.maheshclothes.purchasing.PurchaseRepositories.SupplierPaymentRepository;
import com.maheshclothes.purchasing.PurchaseRepositories.SupplierRepository;
import com.maheshclothes.purchasing.PurchaseRepositories.SupplierReturnItemRepository;
import com.maheshclothes.purchasing.PurchaseRepositories.SupplierReturnRepository;
import com.maheshclothes.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Wholesale purchasing. Stock only increases when a purchase is RECEIVED, and a received invoice can never be
 * received twice (row lock + status check). Mistakes on a received invoice are fixed with supplier returns,
 * never by editing history.
 */
@Service
@RequiredArgsConstructor
public class PurchaseService {

    private final PurchaseInvoiceRepository invoices;
    private final PurchaseItemRepository items;
    private final SupplierRepository suppliers;
    private final SupplierPaymentRepository payments;
    private final SupplierReturnRepository returns;
    private final SupplierReturnItemRepository returnItems;
    private final ProductQueryRepository productQuery;
    private final ProductRepository products;
    private final StockService stock;
    private final NumberingService numbering;
    private final SettingsService settings;
    private final ShopClock clock;
    private final AuditService audit;

    public record PurchaseLine(UUID variantId, int quantity, BigDecimal unitPrice, BigDecimal discount, BigDecimal taxRate) {
    }

    public record PurchaseRequest(UUID id, UUID supplierId, String supplierInvoiceNo, LocalDate purchaseDate,
                                  List<PurchaseLine> lines, BigDecimal additionalCosts, String notes,
                                  UUID attachmentFileId, boolean receiveNow) {
    }

    public PurchaseInvoice get(UUID id) {
        return invoices.findById(id).orElseThrow(() -> new NotFoundException("Purchase"));
    }

    /** Creates or updates a DRAFT; with receiveNow the stock receipt is confirmed in the same transaction. */
    @Transactional
    public PurchaseInvoice save(PurchaseRequest r) {
        Supplier supplier = suppliers.findById(r.supplierId()).orElseThrow(() -> new NotFoundException("Supplier"));
        if (!supplier.isActive()) {
            throw new BusinessException("purchase.error.supplier.inactive");
        }
        String invNo = r.supplierInvoiceNo() == null ? "" : r.supplierInvoiceNo().trim();
        if (invNo.isEmpty()) {
            throw new BusinessException("purchase.error.invoiceno");
        }
        if (r.purchaseDate() == null || r.purchaseDate().isAfter(clock.today())) {
            throw new BusinessException("purchase.error.date");
        }
        if (r.lines() == null || r.lines().isEmpty()) {
            throw new BusinessException("purchase.error.empty");
        }
        Set<UUID> seen = new HashSet<>();
        for (PurchaseLine l : r.lines()) {
            if (!seen.add(l.variantId())) {
                throw new BusinessException("purchase.error.duplicate.line");
            }
        }
        Map<UUID, VariantInfo> info = productQuery.variantInfo(seen);
        if (info.size() != seen.size()) {
            throw new BusinessException("error.notfound", "Variant");
        }

        PurchaseInvoice inv;
        if (r.id() == null) {
            inv = new PurchaseInvoice();
            inv.setPurchaseNo(numbering.nextDocument("PURCHASE", "PUR", clock.today()));
        } else {
            inv = invoices.lockById(r.id()).orElseThrow(() -> new NotFoundException("Purchase"));
            if (inv.getStatus() != PurchaseStatus.DRAFT) {
                throw new BusinessException("purchase.error.notdraft");
            }
            items.deleteByPurchaseId(inv.getId());
            items.flush();
        }
        final UUID selfId = inv.getId();
        boolean duplicate = invoices.findBySupplierIdAndSupplierInvoiceNoIgnoreCaseAndStatusNot(
                        r.supplierId(), invNo, PurchaseStatus.CANCELLED).stream()
                .anyMatch(x -> !x.getId().equals(selfId));
        if (duplicate) {
            throw new BusinessException("purchase.error.duplicate.invoice");
        }

        List<PurchaseCalculator.LineInput> inputs = r.lines().stream()
                .map(l -> new PurchaseCalculator.LineInput(l.quantity(), l.unitPrice(), l.discount(), l.taxRate()))
                .toList();
        PurchaseCalculator.Result calc = PurchaseCalculator.compute(inputs, r.additionalCosts(),
                settings.bool("purchase.tax_in_cost"));

        inv.setSupplierId(r.supplierId());
        inv.setSupplierInvoiceNo(invNo);
        inv.setPurchaseDate(r.purchaseDate());
        inv.setStatus(PurchaseStatus.DRAFT);
        inv.setSubtotal(calc.subtotal());
        inv.setDiscountTotal(calc.discountTotal());
        inv.setTaxTotal(calc.taxTotal());
        inv.setAdditionalCosts(calc.additionalCosts());
        inv.setTotal(calc.total());
        inv.setNotes(r.notes() == null || r.notes().isBlank() ? null : r.notes().trim());
        if (r.attachmentFileId() != null) {
            inv.setAttachmentFileId(r.attachmentFileId());
        }
        inv = invoices.save(inv);

        List<PurchaseItem> rows = new ArrayList<>();
        for (int i = 0; i < r.lines().size(); i++) {
            PurchaseLine l = r.lines().get(i);
            PurchaseCalculator.LineResult lr = calc.lines().get(i);
            PurchaseItem it = new PurchaseItem();
            it.setPurchaseId(inv.getId());
            it.setVariantId(l.variantId());
            it.setQuantity(l.quantity());
            it.setUnitPrice(Money.scale(l.unitPrice()));
            it.setDiscount(lr.discount());
            it.setTaxRate(Money.nz(l.taxRate()));
            it.setTaxAmount(lr.taxAmount());
            it.setLineTotal(lr.lineTotal());
            it.setLandedUnitCost(lr.landedUnitCost());
            rows.add(it);
        }
        items.saveAll(rows);
        audit.log(r.id() == null ? "PURCHASE_DRAFT_CREATED" : "PURCHASE_DRAFT_UPDATED", "purchase", inv.getId(),
                inv.getPurchaseNo() + " total " + inv.getTotal());
        return r.receiveNow() ? receive(inv.getId()) : inv;
    }

    /** Confirms the goods receipt: stock goes up and the weighted-average cost is recalculated. */
    @Transactional
    public PurchaseInvoice receive(UUID id) {
        PurchaseInvoice inv = invoices.lockById(id).orElseThrow(() -> new NotFoundException("Purchase"));
        if (inv.getStatus() != PurchaseStatus.DRAFT) {
            throw new BusinessException("purchase.error.alreadyreceived");
        }
        List<PurchaseItem> rows = items.findByPurchaseId(id);
        if (rows.isEmpty()) {
            throw new BusinessException("purchase.error.empty");
        }
        Map<UUID, StockBalance> balances = stock.lock(rows.stream().map(PurchaseItem::getVariantId).toList());
        Map<UUID, VariantInfo> info = productQuery.variantInfo(balances.keySet());
        StockService.Ref ref = new StockService.Ref("PURCHASE", inv.getId(), inv.getPurchaseNo());
        for (PurchaseItem it : rows) {
            stock.receive(balances.get(it.getVariantId()), it.getQuantity(), it.getLandedUnitCost(),
                    MovementType.PURCHASE_RECEIPT, ref, "Supplier invoice " + inv.getSupplierInvoiceNo());
            VariantInfo vi = info.get(it.getVariantId());
            if (vi != null) {
                Product p = products.findById(vi.productId()).orElse(null);
                if (p != null) {          // keep the product's "last purchase cost" up to date for the product screens
                    p.setPurchaseCost(Money.scale(it.getLandedUnitCost()));
                    products.save(p);
                }
            }
        }
        inv.setStatus(PurchaseStatus.RECEIVED);
        inv.setReceivedAt(Instant.now());
        inv.setReceivedBy(CurrentUser.username());
        audit.log("PURCHASE_RECEIVED", "purchase", id, inv.getPurchaseNo() + " " + rows.size() + " line(s), total " + inv.getTotal());
        return invoices.save(inv);
    }

    @Transactional
    public void attach(UUID id, UUID fileId) {
        PurchaseInvoice inv = invoices.findById(id).orElseThrow(() -> new NotFoundException("Purchase"));
        inv.setAttachmentFileId(fileId);
        invoices.save(inv);
        audit.log("PURCHASE_ATTACHMENT", "purchase", id, inv.getPurchaseNo());
    }

    @Transactional
    public void cancelDraft(UUID id) {
        PurchaseInvoice inv = invoices.lockById(id).orElseThrow(() -> new NotFoundException("Purchase"));
        if (inv.getStatus() != PurchaseStatus.DRAFT) {
            throw new BusinessException("purchase.error.notdraft");
        }
        inv.setStatus(PurchaseStatus.CANCELLED);
        invoices.save(inv);
        audit.log("PURCHASE_DRAFT_CANCELLED", "purchase", id, inv.getPurchaseNo());
    }

    /** Pays a supplier, either against one received invoice or "on account". */
    @Transactional
    public SupplierPayment pay(UUID supplierId, UUID purchaseId, BigDecimal amount, String method, String reference,
                               LocalDate paidOn, String notes) {
        BigDecimal amt = Money.scale(amount);
        if (amt.signum() <= 0) {
            throw new BusinessException("supplier.error.amount");
        }
        if (method == null || method.isBlank()) {
            throw new BusinessException("payment.error.method");
        }
        if (paidOn == null || paidOn.isAfter(clock.today())) {
            throw new BusinessException("purchase.error.date");
        }
        Supplier supplier = suppliers.findById(supplierId).orElseThrow(() -> new NotFoundException("Supplier"));
        if (purchaseId != null) {
            PurchaseInvoice inv = invoices.lockById(purchaseId).orElseThrow(() -> new NotFoundException("Purchase"));
            if (!inv.getSupplierId().equals(supplierId) || inv.getStatus() != PurchaseStatus.RECEIVED) {
                throw new BusinessException("supplier.error.payment.invoice");
            }
            if (amt.compareTo(inv.outstanding()) > 0) {
                throw new BusinessException("supplier.error.overpay", Money.scale(inv.outstanding()).toPlainString());
            }
            inv.setAmountPaid(inv.getAmountPaid().add(amt));
            invoices.save(inv);
        }
        SupplierPayment p = new SupplierPayment();
        p.setPaymentNo(numbering.nextDocument("SUPPLIER_PAYMENT", "SPAY", clock.today()));
        p.setSupplierId(supplierId);
        p.setPurchaseId(purchaseId);
        p.setAmount(amt);
        p.setMethod(method.trim());
        p.setReference(reference == null || reference.isBlank() ? null : reference.trim());
        p.setPaidOn(paidOn);
        p.setNotes(notes == null || notes.isBlank() ? null : notes.trim());
        p = payments.save(p);
        audit.log("SUPPLIER_PAYMENT", "supplier", supplierId, supplier.getName() + " " + amt + " " + p.getPaymentNo());
        return p;
    }

    /**
     * Returns goods to the supplier: stock goes down, the supplier owes us a credit.
     * qtyByItem maps purchase_item id -> quantity being returned.
     */
    @Transactional
    public SupplierReturn returnToSupplier(UUID purchaseId, Map<UUID, Integer> qtyByItem, String reason, LocalDate date) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("return.error.reason");
        }
        PurchaseInvoice inv = invoices.lockById(purchaseId).orElseThrow(() -> new NotFoundException("Purchase"));
        if (inv.getStatus() != PurchaseStatus.RECEIVED) {
            throw new BusinessException("purchase.error.notreceived");
        }
        List<PurchaseItem> rows = items.findByPurchaseId(purchaseId);
        List<PurchaseItem> chosen = new ArrayList<>();
        for (PurchaseItem it : rows) {
            Integer q = qtyByItem.get(it.getId());
            if (q != null && q > 0) {
                chosen.add(it);
            }
        }
        if (chosen.isEmpty()) {
            throw new BusinessException("return.error.noitems");
        }
        Map<UUID, StockBalance> balances = stock.lock(chosen.stream().map(PurchaseItem::getVariantId).toList());
        Map<UUID, VariantInfo> info = productQuery.variantInfo(balances.keySet());

        SupplierReturn ret = new SupplierReturn();
        ret.setReturnNo(numbering.nextDocument("SUPPLIER_RETURN", "SRET", clock.today()));
        ret.setSupplierId(inv.getSupplierId());
        ret.setPurchaseId(purchaseId);
        ret.setReturnDate(date == null ? clock.today() : date);
        ret.setReason(reason.trim());
        ret = returns.save(ret);

        BigDecimal total = BigDecimal.ZERO;
        StockService.Ref ref = new StockService.Ref("SUPPLIER_RETURN", ret.getId(), ret.getReturnNo());
        for (PurchaseItem it : chosen) {
            int q = qtyByItem.get(it.getId());
            if (q > it.getQuantity() - it.getReturnedQty()) {
                throw new BusinessException("return.error.quantity");
            }
            StockBalance b = balances.get(it.getVariantId());
            if (b.getAvailableQty() < q) {
                VariantInfo vi = info.get(it.getVariantId());
                throw new BusinessException("purchase.error.return.nostock", vi == null ? "" : vi.label(), b.getAvailableQty());
            }
            BigDecimal credit = Proration.portion(it.getLineTotal(), it.getQuantity(), it.getReturnedQty(),
                    it.getReturnedAmount(), q);
            StockMovement m = stock.issue(b, q, MovementType.SUPPLIER_RETURN, ref, reason);
            SupplierReturnItem ri = new SupplierReturnItem();
            ri.setReturnId(ret.getId());
            ri.setPurchaseItemId(it.getId());
            ri.setVariantId(it.getVariantId());
            ri.setQuantity(q);
            ri.setAmount(credit);
            ri.setStockUnitCost(m.getUnitCost());
            returnItems.save(ri);
            it.setReturnedQty(it.getReturnedQty() + q);
            it.setReturnedAmount(it.getReturnedAmount().add(credit));
            items.save(it);
            total = total.add(credit);
        }
        ret.setTotal(Money.scale(total));
        returns.save(ret);
        inv.setReturnedTotal(inv.getReturnedTotal().add(ret.getTotal()));
        invoices.save(inv);
        audit.log("SUPPLIER_RETURN", "purchase", purchaseId, ret.getReturnNo() + " credit " + ret.getTotal() + ": " + reason);
        return ret;
    }
}
