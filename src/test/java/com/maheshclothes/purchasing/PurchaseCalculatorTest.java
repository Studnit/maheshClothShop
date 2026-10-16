package com.maheshclothes.purchasing;

import com.maheshclothes.common.BusinessException;
import com.maheshclothes.purchasing.PurchaseCalculator.LineInput;
import com.maheshclothes.purchasing.PurchaseCalculator.Result;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PurchaseCalculatorTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static final List<LineInput> LINES = List.of(
            new LineInput(10, d("200"), d("100"), d("5")),
            new LineInput(5, d("400"), d("0"), d("5")));

    @Test
    void totalsAndLandedCostWithTaxInCost() {
        Result r = PurchaseCalculator.compute(LINES, d("390"), true);
        assertEquals("4000.00", r.subtotal().toPlainString());
        assertEquals("100.00", r.discountTotal().toPlainString());
        assertEquals("195.00", r.taxTotal().toPlainString());
        assertEquals("4485.00", r.total().toPlainString());
        assertEquals("190.00", r.lines().get(0).allocatedExtra().toPlainString());
        assertEquals("200.00", r.lines().get(1).allocatedExtra().toPlainString());
        assertEquals("218.5000", r.lines().get(0).landedUnitCost().toPlainString());
        assertEquals("460.0000", r.lines().get(1).landedUnitCost().toPlainString());
    }

    @Test
    void landedCostExcludesTaxWhenInputCreditIsClaimed() {
        Result r = PurchaseCalculator.compute(LINES, d("390"), false);
        assertEquals("209.0000", r.lines().get(0).landedUnitCost().toPlainString());
        assertEquals("440.0000", r.lines().get(1).landedUnitCost().toPlainString());
        assertEquals("4485.00", r.total().toPlainString());   // the invoice total itself does not change
    }

    @Test
    void rejectsBadInput() {
        assertEquals("purchase.error.quantity", assertThrows(BusinessException.class, () -> PurchaseCalculator
                .compute(List.of(new LineInput(0, d("10"), d("0"), d("0"))), d("0"), true)).getKey());
        assertEquals("purchase.error.discount", assertThrows(BusinessException.class, () -> PurchaseCalculator
                .compute(List.of(new LineInput(1, d("10"), d("11"), d("0"))), d("0"), true)).getKey());
        assertEquals("purchase.error.empty", assertThrows(BusinessException.class,
                () -> PurchaseCalculator.compute(List.of(), d("0"), true)).getKey());
    }
}
