package com.maheshclothes.sales;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "payment_methods")
@Getter
@Setter
public class PaymentMethod {

    @Id
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(name = "requires_reference", nullable = false)
    private boolean requiresReference;

    @Column(nullable = false)
    private boolean selectable = true;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
}
