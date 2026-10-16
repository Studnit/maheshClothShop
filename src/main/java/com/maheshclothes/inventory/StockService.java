package com.maheshclothes.inventory;

import com.maheshclothes.common.BusinessException;
import com.maheshclothes.common.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The only place stock quantities change. Callers first lock() the balances they need (all in one go, in id order),
 * then call receive / issue / ... which update the balance AND append the ledger line in the same transaction.
 */
@Service
@RequiredArgsConstructor
public class StockService {

    private final StockBalanceRepository balances;
    private final StockMovementRepository movements;

    /** Reference to the document that caused the movement. */
    public record Ref(String type, UUID id, String number) {
        public static final Ref NONE = new Ref(null, null, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, StockBalance> lock(Collection<UUID> variantIds) {
        List<UUID> ids = variantIds.stream().distinct().sorted().toList();
        List<StockBalance> found = balances.lockAll(ids);
        if (found.size() != ids.size()) {
            throw new BusinessException("stock.error.missing");
        }
        Map<UUID, StockBalance> map = new LinkedHashMap<>();
        found.forEach(b -> map.put(b.getVariantId(), b));
        return map;
    }

    /** Sellable stock goes up; the weighted-average cost is recalculated. */
    @Transactional(propagation = Propagation.MANDATORY)
    public StockMovement receive(StockBalance b, int qty, BigDecimal unitCost, MovementType type, Ref ref, String reason) {
        requirePositive(qty);
        BigDecimal cost = unitCost == null ? b.getAvgCost() : unitCost;
        b.setAvgCost(CostCalculator.weightedAverage(b.getAvailableQty(), b.getAvgCost(), qty, cost));
        b.setAvailableQty(b.getAvailableQty() + qty);
        return record(b, type, qty, 0, cost, ref, reason);
    }

    /** Sellable stock goes down at the current average cost (which is returned in the movement). */
    @Transactional(propagation = Propagation.MANDATORY)
    public StockMovement issue(StockBalance b, int qty, MovementType type, Ref ref, String reason) {
        requirePositive(qty);
        if (b.getAvailableQty() < qty) {
            throw new BusinessException("stock.error.insufficient", b.getAvailableQty());
        }
        BigDecimal cost = b.getAvgCost();
        b.setAvailableQty(b.getAvailableQty() - qty);
        return record(b, type, -qty, 0, cost, ref, reason);
    }

    /** Move sellable units to the damaged pile. */
    @Transactional(propagation = Propagation.MANDATORY)
    public StockMovement markDamaged(StockBalance b, int qty, Ref ref, String reason) {
        requirePositive(qty);
        if (b.getAvailableQty() < qty) {
            throw new BusinessException("stock.error.insufficient", b.getAvailableQty());
        }
        BigDecimal cost = b.getAvgCost();
        b.setAvailableQty(b.getAvailableQty() - qty);
        b.setDamagedQty(b.getDamagedQty() + qty);
        return record(b, MovementType.DAMAGE, -qty, qty, cost, ref, reason);
    }

    /** Units arrive already damaged (e.g. a damaged customer return). The average cost of sellable stock is unaffected. */
    @Transactional(propagation = Propagation.MANDATORY)
    public StockMovement receiveDamaged(StockBalance b, int qty, BigDecimal unitCost, MovementType type, Ref ref, String reason) {
        requirePositive(qty);
        b.setDamagedQty(b.getDamagedQty() + qty);
        return record(b, type, 0, qty, unitCost == null ? b.getAvgCost() : unitCost, ref, reason);
    }

    /** Damaged units are thrown away / written off. */
    @Transactional(propagation = Propagation.MANDATORY)
    public StockMovement writeOffDamaged(StockBalance b, int qty, Ref ref, String reason) {
        requirePositive(qty);
        if (b.getDamagedQty() < qty) {
            throw new BusinessException("stock.error.damaged.insufficient", b.getDamagedQty());
        }
        b.setDamagedQty(b.getDamagedQty() - qty);
        return record(b, MovementType.WRITE_OFF, 0, -qty, b.getAvgCost(), ref, reason);
    }

    private StockMovement record(StockBalance b, MovementType type, int availableDelta, int damagedDelta,
                                 BigDecimal unitCost, Ref ref, String reason) {
        b.setUpdatedAt(Instant.now());
        balances.save(b);
        StockMovement m = new StockMovement();
        m.setVariantId(b.getVariantId());
        m.setMovementType(type);
        m.setAvailableDelta(availableDelta);
        m.setDamagedDelta(damagedDelta);
        m.setUnitCost(Money.cost(unitCost));
        m.setAvailableAfter(b.getAvailableQty());
        m.setDamagedAfter(b.getDamagedQty());
        Ref r = ref == null ? Ref.NONE : ref;
        m.setRefType(r.type());
        m.setRefId(r.id());
        m.setRefNo(r.number());
        m.setReason(reason == null ? null : reason.length() > 300 ? reason.substring(0, 300) : reason);
        return movements.save(m);
    }

    private static void requirePositive(int qty) {
        if (qty <= 0) {
            throw new BusinessException("stock.error.quantity");
        }
    }
}
