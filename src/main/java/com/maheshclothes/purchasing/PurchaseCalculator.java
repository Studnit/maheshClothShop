package com.maheshclothes.purchasing;

import com.maheshclothes.common.BusinessException;
import com.maheshclothes.common.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure purchase-invoice maths.
 *  - net  = qty x price - discount
 *  - tax  = net x rate / 100   (supplier prices are tax-exclusive)
 *  - additional costs (freight, packing) are spread over lines in proportion to net value
 *  - landed unit cost = (net + [tax if tax is part of cost] + allocated extra) / qty
 *    Registered shops that claim input tax credit set "purchase.tax_in_cost" to false.
 */
public final class PurchaseCalculator {

    public record LineInput(int quantity, BigDecimal unitPrice, BigDecimal discount, BigDecimal taxRate) {
    }

    public record LineResult(BigDecimal gross, BigDecimal discount, BigDecimal taxAmount, BigDecimal lineTotal,
                             BigDecimal allocatedExtra, BigDecimal landedUnitCost) {
    }

    public record Result(List<LineResult> lines, BigDecimal subtotal, BigDecimal discountTotal,
                         BigDecimal taxTotal, BigDecimal additionalCosts, BigDecimal total) {
    }

    private PurchaseCalculator() {
    }

    public static Result compute(List<LineInput> inputs, BigDecimal additionalCosts, boolean taxInCost) {
        if (inputs == null || inputs.isEmpty()) {
            throw new BusinessException("purchase.error.empty");
        }
        BigDecimal extra = Money.scale(additionalCosts);
        if (extra.signum() < 0) {
            throw new BusinessException("purchase.error.extra.negative");
        }
        int n = inputs.size();
        BigDecimal[] gross = new BigDecimal[n];
        BigDecimal[] disc = new BigDecimal[n];
        BigDecimal[] net = new BigDecimal[n];
        BigDecimal sumNet = BigDecimal.ZERO;
        long sumQty = 0;
        for (int i = 0; i < n; i++) {
            LineInput in = inputs.get(i);
            if (in.quantity() <= 0) {
                throw new BusinessException("purchase.error.quantity");
            }
            if (in.unitPrice() == null || in.unitPrice().signum() < 0) {
                throw new BusinessException("purchase.error.price");
            }
            gross[i] = Money.times(in.unitPrice(), in.quantity());
            disc[i] = Money.scale(in.discount());
            if (disc[i].signum() < 0 || disc[i].compareTo(gross[i]) > 0) {
                throw new BusinessException("purchase.error.discount");
            }
            net[i] = gross[i].subtract(disc[i]);
            sumNet = sumNet.add(net[i]);
            sumQty += in.quantity();
        }

        List<LineResult> lines = new ArrayList<>(n);
        BigDecimal allocated = BigDecimal.ZERO;
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal discTotal = BigDecimal.ZERO;
        BigDecimal taxTotal = BigDecimal.ZERO;
        BigDecimal lineTotals = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            LineInput in = inputs.get(i);
            BigDecimal rate = Money.nz(in.taxRate());
            BigDecimal tax = net[i].multiply(rate).divide(Money.HUNDRED, 2, RoundingMode.HALF_UP);
            BigDecimal lineTotal = net[i].add(tax);

            BigDecimal share;
            if (extra.signum() == 0) {
                share = Money.ZERO;
            } else if (i == n - 1) {
                share = extra.subtract(allocated);
            } else if (sumNet.signum() > 0) {
                share = extra.multiply(net[i]).divide(sumNet, 2, RoundingMode.HALF_UP);
            } else {
                share = extra.multiply(BigDecimal.valueOf(in.quantity()))
                        .divide(BigDecimal.valueOf(sumQty), 2, RoundingMode.HALF_UP);
            }
            allocated = allocated.add(share);

            BigDecimal costValue = net[i].add(share);
            if (taxInCost) {
                costValue = costValue.add(tax);
            }
            BigDecimal landed = costValue.divide(BigDecimal.valueOf(in.quantity()), 4, RoundingMode.HALF_UP);

            lines.add(new LineResult(gross[i], disc[i], Money.scale(tax), Money.scale(lineTotal),
                    Money.scale(share), landed));
            subtotal = subtotal.add(gross[i]);
            discTotal = discTotal.add(disc[i]);
            taxTotal = taxTotal.add(tax);
            lineTotals = lineTotals.add(lineTotal);
        }
        BigDecimal total = lineTotals.add(extra);
        return new Result(lines, Money.scale(subtotal), Money.scale(discTotal), Money.scale(taxTotal),
                extra, Money.scale(total));
    }
}
