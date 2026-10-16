package com.maheshclothes.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SizeRepository extends JpaRepository<Size, UUID> {

    List<Size> findAllByOrderBySortOrderAscNameAsc();

    List<Size> findAllByActiveTrueOrderBySortOrderAscNameAsc();

    boolean existsByNameIgnoreCase(String name);

    java.util.Optional<Size> findByNameIgnoreCase(String name);
}
