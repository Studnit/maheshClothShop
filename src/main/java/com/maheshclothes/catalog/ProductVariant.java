package com.maheshclothes.catalog;

import com.maheshclothes.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.DynamicUpdate;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "product_variants")
@DynamicUpdate
@Getter
@Setter
public class ProductVariant extends BaseEntity {

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(nullable = false, length = 60)
    private String sku;

    @Column(name = "size_id")
    private UUID sizeId;

    @Column(name = "colour_id")
    private UUID colourId;

    @Column(length = 60)
    private String barcode;

    @Column(name = "selling_price")
    private BigDecimal sellingPrice;

    @Column(nullable = false)
    private boolean active = true;

    @Version
    private Long version;
}
