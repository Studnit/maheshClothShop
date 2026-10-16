package com.maheshclothes.catalog;

import com.maheshclothes.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;

/** Shared shape of the configurable lists: categories, brands, sizes, colours (and expense categories). */
@MappedSuperclass
@Getter
@Setter
public abstract class CatalogItem extends BaseEntity {

    @Column(nullable = false, length = 80)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean active = true;
}
