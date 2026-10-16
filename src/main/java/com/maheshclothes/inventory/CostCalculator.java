package com.maheshclothes.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Inventory costing: weighted-average cost over SELLABLE units.
 *  - receiving stock re-averages:  (oldQty x oldAvg + inQty x inCost) / (oldQty + inQty)
 *  - issuing stock (sale, damage, supplier return) leaves the average unchanged
 * The cost of every sale is frozen on the invoice line, so reports stay correct even when
 * the average moves later.
 */
public final class CostCalculator {

    private CostCalculator() {
    }

    public static BigDecimal weightedAverage(int oldQty, BigDecimal oldAvg, int inQty, BigDecimal inCost) {
        BigDecimal avg = oldAvg == null ? BigDecimal.ZERO : oldAvg;
        BigDecimal cost = inCost == null ? BigDecimal.ZERO : inCost;
        int total = oldQty + inQty;
        if (inQty <= 0) {
            return avg.setScale(4, RoundingMode.HALF_UP);
        }
        if (total <= 0 || oldQty <= 0) {
            return cost.setScale(4, RoundingMode.HALF_UP);
        }
        BigDecimal value = avg.multiply(BigDecimal.valueOf(oldQty)).add(cost.multiply(BigDecimal.valueOf(inQty)));
        return value.divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
    }
}
