package com.maheshclothes.inventory;

import com.maheshclothes.common.BusinessException;
import com.maheshclothes.common.Proration;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CostAndProrationTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void weightedAverageOfTwoReceipts() {
        assertEquals("110.0000", CostCalculator.weightedAverage(10, d("100"), 10, d("120")).toPlainString());
        assertEquals("59.0000", CostCalculator.weightedAverage(7, d("50"), 3, d("80")).toPlainString());
        assertEquals("15.7143", CostCalculator.weightedAverage(3, d("10"), 4, d("20")).toPlainString());
    }

    @Test
    void firstReceiptSetsTheCost() {
        assertEquals("80.5000", CostCalculator.weightedAverage(0, d("0"), 5, d("80.5")).toPlainString());
    }

    @Test
    void receivingNothingKeepsTheAverage() {
        assertEquals("100.0000", CostCalculator.weightedAverage(3, d("100"), 0, d("999")).toPlainString());
    }

    @Test
    void partialReturnsAddUpExactly() {
        BigDecimal first = Proration.portion(d("1000"), 3, 0, d("0"), 1);
        BigDecimal second = Proration.portion(d("1000"), 3, 1, first, 1);
        BigDecimal third = Proration.portion(d("1000"), 3, 2, first.add(second), 1);
        assertEquals("333.33", first.toPlainString());
        assertEquals("333.33", second.toPlainString());
        assertEquals("333.34", third.toPlainString());
        assertEquals("1000.00", first.add(second).add(third).toPlainString());
    }

    @Test
    void fullReturnGivesTheWholeAmount() {
        assertEquals("1000.00", Proration.portion(d("1000"), 3, 0, d("0"), 3).toPlainString());
    }

    @Test
    void cannotReturnMoreThanRemains() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> Proration.portion(d("1000"), 3, 2, d("666.66"), 2));
        assertEquals("return.error.quantity", e.getKey());
    }
}
