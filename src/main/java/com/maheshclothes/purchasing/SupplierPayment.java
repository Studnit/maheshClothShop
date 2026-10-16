package com.maheshclothes.purchasing;

import com.maheshclothes.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "supplier_payments")
@Getter
@Setter
public class SupplierPayment extends BaseEntity {

    @Column(name = "payment_no", nullable = false, length = 40)
    private String paymentNo;

    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;

    @Column(name = "purchase_id")
    private UUID purchaseId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 30)
    private String method;

    @Column(length = 100)
    private String reference;

    @Column(name = "paid_on", nullable = false)
    private LocalDate paidOn;

    @Column(length = 300)
    private String notes;
}
