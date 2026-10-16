package com.maheshclothes.sales;

import com.maheshclothes.common.BusinessException;
import com.maheshclothes.common.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure pricing logic for a sales invoice: line discounts, invoice-level discount allocation,
 * tax (inclusive or exclusive) and optional rupee round-off. No framework dependencies.
 *
 * Rules
 *  - gross            = unit price x quantity
 *  - after line disc. = gross - line discount
 *  - invoice discount is spread over lines in proportion to their post-line-discount value
 *    (the last line absorbs rounding), so tax is always computed on the true discounted price
 *  - tax-inclusive prices:  tax = net x r / (100 + r),  taxable = net - tax,  total = net
 *  - tax-exclusive prices:  taxable = net,  tax = net x r / 100,  total = net + tax
 *  - always: line total = taxable + tax
 */
public final class PricingCalculator {

    public record LineInput(BigDecimal unitPrice, int quantity, BigDecimal lineDiscount, BigDecimal taxRate) {
    }

    public record LineResult(BigDecimal gross, BigDecimal lineDiscount, BigDecimal invoiceDiscountShare,
                             BigDecimal taxableValue, BigDecimal taxRate, BigDecimal taxAmount,
                             BigDecimal lineTotal) {
    }

    public record Result(List<LineResult> lines, BigDecimal subtotal, BigDecimal lineDiscountTotal,
                         BigDecimal invoiceDiscount, BigDecimal taxableTotal, BigDecimal taxTotal,
                         BigDecimal roundOff, BigDecimal grandTotal) {
    }

    private PricingCalculator() {
    }

    public static Result compute(List<LineInput> inputs, BigDecimal invoiceDiscount,
                                 boolean pricesIncludeTax, boolean roundOff) {
        if (inputs == null || inputs.isEmpty()) {
            throw new BusinessException("sale.error.empty");
        }
        BigDecimal invDisc = Money.scale(invoiceDiscount);
        if (invDisc.signum() < 0) {
            throw new BusinessException("sale.error.discount.negative");
        }

        int n = inputs.size();
        BigDecimal[] gross = new BigDecimal[n];
        BigDecimal[] lineDisc = new BigDecimal[n];
        BigDecimal[] net1 = new BigDecimal[n];
        BigDecimal sumNet1 = BigDecimal.ZERO;
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal lineDiscTotal = BigDecimal.ZERO;

        for (int i = 0; i < n; i++) {
            LineInput in = inputs.get(i);
            if (in.quantity() <= 0) {
                throw new BusinessException("sale.error.quantity");
            }
            gross[i] = Money.times(in.unitPrice(), in.quantity());
            lineDisc[i] = Money.scale(in.lineDiscount());
            if (lineDisc[i].signum() < 0) {
                throw new BusinessException("sale.error.discount.negative");
            }
            if (lineDisc[i].compareTo(gross[i]) > 0) {
                throw new BusinessException("sale.error.discount.exceeds");
            }
            net1[i] = gross[i].subtract(lineDisc[i]);
            sumNet1 = sumNet1.add(net1[i]);
            subtotal = subtotal.add(gross[i]);
            lineDiscTotal = lineDiscTotal.add(lineDisc[i]);
        }
        if (invDisc.compareTo(sumNet1) > 0) {
            throw new BusinessException("sale.error.discount.exceeds");
        }

        // allocate the invoice-level discount
        BigDecimal[] share = new BigDecimal[n];
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            if (invDisc.signum() == 0 || sumNet1.signum() == 0) {
                share[i] = Money.ZERO;
            } else if (i == n - 1) {
                share[i] = invDisc.subtract(allocated).min(net1[i]).max(Money.ZERO);
            } else {
                share[i] = invDisc.multiply(net1[i]).divide(sumNet1, 2, RoundingMode.HALF_UP).min(net1[i]);
            }
            allocated = allocated.add(share[i]);
        }

        List<LineResult> results = new ArrayList<>(n);
        BigDecimal taxableTotal = BigDecimal.ZERO;
        BigDecimal taxTotal = BigDecimal.ZERO;
        BigDecimal grand = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            BigDecimal rate = Money.nz(inputs.get(i).taxRate());
            BigDecimal net = net1[i].subtract(share[i]);
            BigDecimal tax;
            BigDecimal taxable;
            BigDecimal total;
            if (rate.signum() == 0) {
                tax = Money.ZERO;
                taxable = net;
                total = net;
            } else if (pricesIncludeTax) {
                tax = net.multiply(rate).divide(Money.HUNDRED.add(rate), 2, RoundingMode.HALF_UP);
                taxable = net.subtract(tax);
                total = net;
            } else {
                tax = net.multiply(rate).divide(Money.HUNDRED, 2, RoundingMode.HALF_UP);
                taxable = net;
                total = net.add(tax);
            }
            results.add(new LineResult(gross[i], lineDisc[i], share[i], Money.scale(taxable), rate,
                    Money.scale(tax), Money.scale(total)));
            taxableTotal = taxableTotal.add(taxable);
            taxTotal = taxTotal.add(tax);
            grand = grand.add(total);
        }

        BigDecimal roundOffAmount = Money.ZERO;
        if (roundOff) {
            BigDecimal rounded = grand.setScale(0, RoundingMode.HALF_UP).setScale(2);
            roundOffAmount = rounded.subtract(grand);
            grand = rounded;
        }
        return new Result(results, Money.scale(subtotal), Money.scale(lineDiscTotal), invDisc,
                Money.scale(taxableTotal), Money.scale(taxTotal), Money.scale(roundOffAmount), Money.scale(grand));
    }
}
