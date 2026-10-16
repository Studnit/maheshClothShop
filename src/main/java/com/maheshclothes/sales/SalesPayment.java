package com.maheshclothes.sales;

import com.maheshclothes.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sales_payments")
@Getter
@Setter
public class SalesPayment extends BaseEntity {

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private UUID invoiceId;

    /** SALE (at billing), BALANCE (collected later) or EXCHANGE_CREDIT. */
    @Column(name = "payment_kind", nullable = false)
    private String paymentKind = "SALE";

    @Column(nullable = false)
    private String method;

    @Column(nullable = false)
    private BigDecimal amount;

    private String reference;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt = Instant.now();
}
