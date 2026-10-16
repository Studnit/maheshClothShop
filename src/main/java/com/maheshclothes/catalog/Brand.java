package com.maheshclothes.catalog;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "brands")
public class Brand extends CatalogItem {
}
