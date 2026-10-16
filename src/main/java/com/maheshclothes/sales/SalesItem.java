package com.maheshclothes.sales;

import com.maheshclothes.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/** One invoice line. Product name/SKU/size/colour/HSN/cost are frozen copies, so history never changes. */
@Entity
@Table(name = "sales_items")
@Getter
@Setter
public class SalesItem extends BaseEntity {

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private UUID invoiceId;

    @Column(name = "variant_id", nullable = false, updatable = false)
    private UUID variantId;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(nullable = false)
    private String sku;

    @Column(name = "size_name")
    private String sizeName;

    @Column(name = "colour_name")
    private String colourName;

    @Column(name = "hsn_code")
    private String hsnCode;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "unit_price", nullable = false)
    private BigDecimal unitPrice;

    @Column(name = "line_discount", nullable = false)
    private BigDecimal lineDiscount = BigDecimal.ZERO;

    @Column(name = "invoice_discount_share", nullable = false)
    private BigDecimal invoiceDiscountShare = BigDecimal.ZERO;

    @Column(name = "taxable_value", nullable = false)
    private BigDecimal taxableValue;

    @Column(name = "tax_rate", nullable = false)
    private BigDecimal taxRate = BigDecimal.ZERO;

    @Column(name = "tax_amount", nullable = false)
    private BigDecimal taxAmount = BigDecimal.ZERO;

    @Column(name = "line_total", nullable = false)
    private BigDecimal lineTotal;

    @Column(name = "unit_cost", nullable = false)
    private BigDecimal unitCost = BigDecimal.ZERO;

    @Column(name = "returned_qty", nullable = false)
    private int returnedQty;

    @Column(name = "returned_taxable", nullable = false)
    private BigDecimal returnedTaxable = BigDecimal.ZERO;

    @Column(name = "returned_tax", nullable = false)
    private BigDecimal returnedTax = BigDecimal.ZERO;

    @Column(name = "returned_total", nullable = false)
    private BigDecimal returnedTotal = BigDecimal.ZERO;
}
