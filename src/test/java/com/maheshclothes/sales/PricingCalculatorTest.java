package com.maheshclothes.sales;

import com.maheshclothes.common.BusinessException;
import com.maheshclothes.sales.PricingCalculator.LineInput;
import com.maheshclothes.sales.PricingCalculator.Result;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PricingCalculatorTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static LineInput line(String price, int qty, String disc, String rate) {
        return new LineInput(d(price), qty, d(disc), d(rate));
    }

    @Test
    void taxInclusiveSingleLine() {
        Result r = PricingCalculator.compute(List.of(line("1050", 1, "0", "5")), d("0"), true, false);
        assertEquals("50.00", r.taxTotal().toPlainString());
        assertEquals("1000.00", r.taxableTotal().toPlainString());
        assertEquals("1050.00", r.grandTotal().toPlainString());
    }

    @Test
    void taxExclusiveWithLineDiscount() {
        Result r = PricingCalculator.compute(List.of(line("1000", 2, "100", "12")), d("0"), false, false);
        assertEquals("2000.00", r.subtotal().toPlainString());
        assertEquals("100.00", r.lineDiscountTotal().toPlainString());
        assertEquals("1900.00", r.taxableTotal().toPlainString());
        assertEquals("228.00", r.taxTotal().toPlainString());
        assertEquals("2128.00", r.grandTotal().toPlainString());
    }

    @Test
    void invoiceDiscountIsSharedProportionally() {
        Result r = PricingCalculator.compute(
                List.of(line("600", 1, "0", "0"), line("400", 1, "0", "0")), d("100"), true, false);
        assertEquals("60.00", r.lines().get(0).invoiceDiscountShare().toPlainString());
        assertEquals("40.00", r.lines().get(1).invoiceDiscountShare().toPlainString());
        assertEquals("540.00", r.lines().get(0).lineTotal().toPlainString());
        assertEquals("360.00", r.lines().get(1).lineTotal().toPlainString());
        assertEquals("900.00", r.grandTotal().toPlainString());
    }

    @Test
    void invoiceDiscountRoundingNeverLosesAPaisa() {
        Result r = PricingCalculator.compute(
                List.of(line("100", 1, "0", "0"), line("100", 1, "0", "0"), line("100", 1, "0", "0")),
                d("10"), true, false);
        assertEquals("3.33", r.lines().get(0).invoiceDiscountShare().toPlainString());
        assertEquals("3.33", r.lines().get(1).invoiceDiscountShare().toPlainString());
        assertEquals("3.34", r.lines().get(2).invoiceDiscountShare().toPlainString());
        assertEquals("290.00", r.grandTotal().toPlainString());
    }

    @Test
    void taxIsComputedOnTheDiscountedPrice() {
        Result r = PricingCalculator.compute(List.of(line("1180", 1, "0", "18")), d("180"), true, false);
        assertEquals("152.54", r.taxTotal().toPlainString());
        assertEquals("847.46", r.taxableTotal().toPlainString());
        assertEquals("1000.00", r.grandTotal().toPlainString());
    }

    @Test
    void lineTotalAlwaysEqualsTaxablePlusTax() {
        Result r = PricingCalculator.compute(
                List.of(line("999.99", 3, "10.01", "5"), line("249.50", 2, "0", "12"), line("75", 1, "0", "0")),
                d("33.33"), true, false);
        r.lines().forEach(l -> assertEquals(l.lineTotal().toPlainString(),
                l.taxableValue().add(l.taxAmount()).toPlainString()));
    }

    @Test
    void roundOffUp() {
        Result r = PricingCalculator.compute(List.of(line("99.60", 1, "0", "0")), d("0"), true, true);
        assertEquals("100.00", r.grandTotal().toPlainString());
        assertEquals("0.40", r.roundOff().toPlainString());
    }

    @Test
    void roundOffDown() {
        Result r = PricingCalculator.compute(List.of(line("99.40", 1, "0", "0")), d("0"), true, true);
        assertEquals("99.00", r.grandTotal().toPlainString());
        assertEquals("-0.40", r.roundOff().toPlainString());
    }

    @Test
    void rejectsDiscountLargerThanTheItem() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> PricingCalculator.compute(List.of(line("100", 1, "100.01", "0")), d("0"), true, false));
        assertEquals("sale.error.discount.exceeds", e.getKey());
    }

    @Test
    void rejectsInvoiceDiscountLargerThanTheBill() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> PricingCalculator.compute(List.of(line("100", 1, "0", "0")), d("100.01"), true, false));
        assertEquals("sale.error.discount.exceeds", e.getKey());
    }

    @Test
    void rejectsZeroQuantityAndEmptyBills() {
        assertEquals("sale.error.quantity", assertThrows(BusinessException.class,
                () -> PricingCalculator.compute(List.of(line("100", 0, "0", "0")), d("0"), true, false)).getKey());
        assertEquals("sale.error.empty", assertThrows(BusinessException.class,
                () -> PricingCalculator.compute(List.of(), d("0"), true, false)).getKey());
    }

    @Test
    void fullDiscountGivesZeroTotal() {
        Result r = PricingCalculator.compute(List.of(line("500", 1, "0", "5")), d("500"), true, false);
        assertEquals("0.00", r.grandTotal().toPlainString());
        assertEquals("0.00", r.taxTotal().toPlainString());
    }
}
