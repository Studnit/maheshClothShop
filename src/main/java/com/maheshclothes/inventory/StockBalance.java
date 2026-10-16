package com.maheshclothes.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Current stock of one variant. availableQty = sellable units, damagedQty = units set aside.
 * avgCost is the weighted-average cost of the sellable units. The database also refuses negative quantities.
 */
@Entity
@Table(name = "stock_balances")
@Getter
@Setter
@NoArgsConstructor
public class StockBalance {

    @Id
    @Column(name = "variant_id")
    private UUID variantId;

    @Column(name = "available_qty", nullable = false)
    private int availableQty;

    @Column(name = "damaged_qty", nullable = false)
    private int damagedQty;

    @Column(name = "avg_cost", nullable = false)
    private BigDecimal avgCost = BigDecimal.ZERO;

    @Version
    private Long version;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public StockBalance(UUID variantId) {
        this.variantId = variantId;
    }
}
