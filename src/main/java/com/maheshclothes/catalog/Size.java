package com.maheshclothes.catalog;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "sizes")
public class Size extends CatalogItem {
}
