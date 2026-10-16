package com.maheshclothes.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "colours")
@Getter
@Setter
public class Colour extends CatalogItem {

    @Column(name = "hex_code", length = 7)
    private String hexCode;
}
