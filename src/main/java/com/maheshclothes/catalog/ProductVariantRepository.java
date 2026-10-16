package com.maheshclothes.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductVariantRepository extends JpaRepository<ProductVariant, UUID> {

    List<ProductVariant> findByProductId(UUID productId);

    boolean existsBySkuIgnoreCase(String sku);

    Optional<ProductVariant> findBySkuIgnoreCase(String sku);

    boolean existsByBarcode(String barcode);
}
