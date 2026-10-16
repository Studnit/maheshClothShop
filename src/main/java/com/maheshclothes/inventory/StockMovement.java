package com.maheshclothes.inventory;

import com.maheshclothes.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.util.UUID;

/** One line of the stock ledger. Never updated or deleted (the database enforces that). */
@Entity
@Table(name = "stock_movements")
@Immutable
@Getter
@Setter
public class StockMovement extends BaseEntity {

    @Column(name = "variant_id", nullable = false)
    private UUID variantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, length = 30)
    private MovementType movementType;

    @Column(name = "available_delta", nullable = false)
    private int availableDelta;

    @Column(name = "damaged_delta", nullable = false)
    private int damagedDelta;

    @Column(name = "unit_cost", nullable = false)
    private BigDecimal unitCost = BigDecimal.ZERO;

    @Column(name = "available_after", nullable = false)
    private int availableAfter;

    @Column(name = "damaged_after", nullable = false)
    private int damagedAfter;

    @Column(name = "ref_type", length = 30)
    private String refType;

    @Column(name = "ref_id")
    private UUID refId;

    @Column(name = "ref_no", length = 60)
    private String refNo;

    @Column(length = 300)
    private String reason;
}
