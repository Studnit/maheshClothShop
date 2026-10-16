package com.maheshclothes.inventory;

import com.maheshclothes.catalog.ProductQueryRepository;
import com.maheshclothes.catalog.ProductQueryRepository.VariantInfo;
import com.maheshclothes.common.AuditService;
import com.maheshclothes.common.BusinessException;
import com.maheshclothes.common.Money;
import com.maheshclothes.common.NumberingService;
import com.maheshclothes.common.ShopClock;
import com.maheshclothes.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Manual stock changes: opening stock, adjustments, damage, write-off and physical-count reconciliation. */
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final StockService stock;
    private final StockAdjustmentRepository adjustments;
    private final ProductQueryRepository products;
    private final NumberingService numbering;
    private final ShopClock clock;
    private final AuditService audit;

    @Transactional
    public StockAdjustment adjust(UUID variantId, AdjustType type, int quantity, BigDecimal unitCost, String reason) {
        if (quantity <= 0) {
            throw new BusinessException("stock.error.quantity");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("stock.error.reason");
        }
        requirePermission(type);
        VariantInfo info = products.variantInfo(List.of(variantId)).get(variantId);
        if (info == null) {
            throw new BusinessException("error.notfound", "Variant");
        }
        StockBalance b = stock.lock(List.of(variantId)).get(variantId);
        String no = numbering.nextDocument("ADJUSTMENT", "ADJ", clock.today());
        StockAdjustment a = new StockAdjustment();
        a.setAdjustmentNo(no);
        a.setVariantId(variantId);
        a.setAdjustType(type);
        a.setQuantity(quantity);
        a.setReason(reason.trim());
        a = adjustments.save(a);
        StockService.Ref ref = new StockService.Ref("ADJUSTMENT", a.getId(), no);

        switch (type) {
            case OPENING -> {
                BigDecimal cost = requireCost(unitCost);
                a.setUnitCost(cost);
                stock.receive(b, quantity, cost, MovementType.OPENING, ref, reason);
            }
            case ADJUST_IN -> {
                BigDecimal cost = unitCost != null ? requireCost(unitCost) : b.getAvgCost();
                a.setUnitCost(cost);
                stock.receive(b, quantity, cost, MovementType.ADJUST_IN, ref, reason);
            }
            case ADJUST_OUT -> stock.issue(b, quantity, MovementType.ADJUST_OUT, ref, reason);
            case DAMAGE -> stock.markDamaged(b, quantity, ref, reason);
            case WRITE_OFF -> stock.writeOffDamaged(b, quantity, ref, reason);
            case COUNT_GAIN -> stock.receive(b, quantity, b.getAvgCost(), MovementType.COUNT, ref, reason);
            case COUNT_LOSS -> stock.issue(b, quantity, MovementType.COUNT, ref, reason);
        }
        audit.log("STOCK_" + type.name(), "variant", variantId,
                info.label() + " x" + quantity + " (" + no + "): " + reason);
        return adjustments.save(a);
    }

    /**
     * Physical stock count. counted maps variantId -> units actually found on the shelf; any difference from the
     * system quantity is booked as a COUNT_GAIN / COUNT_LOSS adjustment. Returns how many variants changed.
     */
    @Transactional
    public int reconcile(Map<UUID, Integer> counted, String note) {
        if (!CurrentUser.has("STOCK_APPROVE")) {
            throw new BusinessException("stock.error.approve");
        }
        Map<UUID, StockBalance> locked = stock.lock(counted.keySet());
        int changed = 0;
        Map<UUID, Integer> deltas = new LinkedHashMap<>();
        for (Map.Entry<UUID, Integer> e : counted.entrySet()) {
            if (e.getValue() == null || e.getValue() < 0) {
                throw new BusinessException("stock.error.count.negative");
            }
            int diff = e.getValue() - locked.get(e.getKey()).getAvailableQty();
            if (diff != 0) {
                deltas.put(e.getKey(), diff);
            }
        }
        for (Map.Entry<UUID, Integer> d : deltas.entrySet()) {
            adjust(d.getKey(), d.getValue() > 0 ? AdjustType.COUNT_GAIN : AdjustType.COUNT_LOSS, Math.abs(d.getValue()),
                    null, "Physical count" + (note == null || note.isBlank() ? "" : ": " + note.trim()));
            changed++;
        }
        audit.log("STOCK_COUNT_RECONCILED", "inventory", null, changed + " variant(s) corrected of " + counted.size() + " counted");
        return changed;
    }

    private static void requirePermission(AdjustType type) {
        String needed = type.isSensitive() ? "STOCK_APPROVE" : "STOCK_ADJUST";
        if (!CurrentUser.has(needed)) {
            throw new BusinessException("stock.error.approve");
        }
    }

    private static BigDecimal requireCost(BigDecimal cost) {
        if (cost == null || cost.signum() < 0) {
            throw new BusinessException("stock.error.cost");
        }
        return Money.cost(cost);
    }
}
