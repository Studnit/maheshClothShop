package com.maheshclothes.inventory;

import com.maheshclothes.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "stock_adjustments")
@Getter
@Setter
public class StockAdjustment extends BaseEntity {

    @Column(name = "adjustment_no", nullable = false, length = 40)
    private String adjustmentNo;

    @Column(name = "variant_id", nullable = false)
    private UUID variantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "adjust_type", nullable = false, length = 20)
    private AdjustType adjustType;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "unit_cost")
    private BigDecimal unitCost;

    @Column(nullable = false, length = 300)
    private String reason;
}
