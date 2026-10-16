package com.maheshclothes.inventory;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface StockBalanceRepository extends JpaRepository<StockBalance, UUID> {

    /**
     * SELECT ... FOR UPDATE in a fixed (id) order. Two cashiers selling the same shirt queue up here instead of
     * both reading "1 left"; a fixed order means two bills with several shared items cannot deadlock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from StockBalance b where b.variantId in :ids order by b.variantId")
    List<StockBalance> lockAll(@Param("ids") Collection<UUID> ids);
}
