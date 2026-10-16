package com.maheshclothes.sales;

import com.maheshclothes.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sales_invoices")
@Getter
@Setter
public class SalesInvoice extends BaseEntity {

    @Column(name = "invoice_no", nullable = false, updatable = false)
    private String invoiceNo;

    @Column(name = "sale_time", nullable = false)
    private Instant saleTime = Instant.now();

    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "customer_name")
    private String customerName;

    @Column(name = "customer_phone")
    private String customerPhone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SaleStatus status = SaleStatus.COMPLETED;

    @Column(name = "prices_include_tax", nullable = false)
    private boolean pricesIncludeTax = true;

    @Column(nullable = false)
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Column(name = "line_discount_total", nullable = false)
    private BigDecimal lineDiscountTotal = BigDecimal.ZERO;

    @Column(name = "invoice_discount", nullable = false)
    private BigDecimal invoiceDiscount = BigDecimal.ZERO;

    @Column(name = "taxable_total", nullable = false)
    private BigDecimal taxableTotal = BigDecimal.ZERO;

    @Column(name = "tax_total", nullable = false)
    private BigDecimal taxTotal = BigDecimal.ZERO;

    @Column(name = "round_off", nullable = false)
    private BigDecimal roundOff = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal total = BigDecimal.ZERO;

    @Column(name = "amount_paid", nullable = false)
    private BigDecimal amountPaid = BigDecimal.ZERO;

    @Column(name = "balance_due", nullable = false)
    private BigDecimal balanceDue = BigDecimal.ZERO;

    @Column(name = "total_cost", nullable = false)
    private BigDecimal totalCost = BigDecimal.ZERO;

    @Column(name = "client_request_id", nullable = false, updatable = false)
    private UUID clientRequestId;

    @Column(name = "exchange_return_id")
    private UUID exchangeReturnId;

    private String notes;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancelled_by")
    private String cancelledBy;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Version
    private Long version;
}
