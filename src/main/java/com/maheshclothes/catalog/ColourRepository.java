package com.maheshclothes.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ColourRepository extends JpaRepository<Colour, UUID> {

    List<Colour> findAllByOrderBySortOrderAscNameAsc();

    List<Colour> findAllByActiveTrueOrderBySortOrderAscNameAsc();

    boolean existsByNameIgnoreCase(String name);

    Optional<Colour> findByNameIgnoreCase(String name);
}
