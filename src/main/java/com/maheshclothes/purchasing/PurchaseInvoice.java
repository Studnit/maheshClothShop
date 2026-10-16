package com.maheshclothes.purchasing;

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
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "purchase_invoices")
@Getter
@Setter
public class PurchaseInvoice extends BaseEntity {

    @Column(name = "purchase_no", nullable = false, length = 40)
    private String purchaseNo;

    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;

    @Column(name = "supplier_invoice_no", nullable = false, length = 60)
    private String supplierInvoiceNo;

    @Column(name = "purchase_date", nullable = false)
    private LocalDate purchaseDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private PurchaseStatus status = PurchaseStatus.DRAFT;

    @Column(nullable = false)
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Column(name = "discount_total", nullable = false)
    private BigDecimal discountTotal = BigDecimal.ZERO;

    @Column(name = "tax_total", nullable = false)
    private BigDecimal taxTotal = BigDecimal.ZERO;

    @Column(name = "additional_costs", nullable = false)
    private BigDecimal additionalCosts = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal total = BigDecimal.ZERO;

    @Column(name = "amount_paid", nullable = false)
    private BigDecimal amountPaid = BigDecimal.ZERO;

    @Column(name = "returned_total", nullable = false)
    private BigDecimal returnedTotal = BigDecimal.ZERO;

    @Column(length = 500)
    private String notes;

    @Column(name = "attachment_file_id")
    private UUID attachmentFileId;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "received_by", length = 60)
    private String receivedBy;

    @Version
    private Long version;

    /** What we still owe the supplier for this invoice. */
    public BigDecimal outstanding() {
        return total.subtract(returnedTotal).subtract(amountPaid);
    }
}
