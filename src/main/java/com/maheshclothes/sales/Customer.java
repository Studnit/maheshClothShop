package com.maheshclothes.sales;

import com.maheshclothes.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "customers")
@Getter
@Setter
public class Customer extends BaseEntity {

    @Column(name = "customer_code", nullable = false, updatable = false)
    private String customerCode;

    @Column(nullable = false)
    private String name;

    private String phone;

    private String address;

    private String notes;

    @Column(name = "credit_limit")
    private BigDecimal creditLimit;

    @Column(nullable = false)
    private boolean active = true;
}
