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
@Table(name = "products")
@DynamicUpdate
@Getter
@Setter
public class Product extends BaseEntity {

    @Column(name = "product_code", nullable = false, length = 20)
    private String productCode;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 40)
    private String sku;

    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    @Column(name = "brand_id")
    private UUID brandId;

    @Column(columnDefinition = "text")
    private String description;

    @Column(length = 80)
    private String fabric;

    @Column(length = 80)
    private String design;

    @Column(name = "purchase_cost", nullable = false)
    private BigDecimal purchaseCost = BigDecimal.ZERO;

    @Column(name = "selling_price", nullable = false)
    private BigDecimal sellingPrice = BigDecimal.ZERO;

    private BigDecimal mrp;

    @Column(name = "tax_rate", nullable = false)
    private BigDecimal taxRate = BigDecimal.ZERO;

    @Column(name = "hsn_code", length = 12)
    private String hsnCode;

    @Column(name = "min_stock")
    private Integer minStock;

    @Column(length = 60)
    private String barcode;

    @Column(name = "image_file_id")
    private UUID imageFileId;

    @Column(nullable = false)
    private boolean active = true;

    @Version
    private Long version;
}
