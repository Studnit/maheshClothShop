package com.maheshclothes.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Splits an amount over units for partial returns without ever drifting by a paisa:
 * a partial return gets round(total x returning / totalQty); the return that takes the LAST remaining
 * units gets exactly what is left, so all returns add up to the original amount.
 */
public final class Proration {

    private Proration() {
    }

    public static BigDecimal portion(BigDecimal total, int totalQty, int alreadyReturnedQty,
                                     BigDecimal alreadyReturnedAmount, int returningQty) {
        if (returningQty <= 0 || returningQty > totalQty - alreadyReturnedQty) {
            throw new BusinessException("return.error.quantity");
        }
        BigDecimal t = Money.scale(total);
        if (returningQty == totalQty - alreadyReturnedQty) {
            return t.subtract(Money.scale(alreadyReturnedAmount));
        }
        return t.multiply(BigDecimal.valueOf(returningQty))
                .divide(BigDecimal.valueOf(totalQty), 2, RoundingMode.HALF_UP);
    }
}
