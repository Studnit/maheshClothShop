package com.maheshclothes.purchasing;

import com.maheshclothes.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "supplier_return_items")
@Getter
@Setter
public class SupplierReturnItem extends BaseEntity {

    @Column(name = "return_id", nullable = false)
    private UUID returnId;

    @Column(name = "purchase_item_id", nullable = false)
    private UUID purchaseItemId;

    @Column(name = "variant_id", nullable = false)
    private UUID variantId;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "stock_unit_cost", nullable = false)
    private BigDecimal stockUnitCost;
}
