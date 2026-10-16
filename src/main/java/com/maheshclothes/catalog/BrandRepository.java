package com.maheshclothes.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BrandRepository extends JpaRepository<Brand, UUID> {

    List<Brand> findAllByOrderBySortOrderAscNameAsc();

    List<Brand> findAllByActiveTrueOrderBySortOrderAscNameAsc();

    boolean existsByNameIgnoreCase(String name);

    java.util.Optional<Brand> findByNameIgnoreCase(String name);
}
