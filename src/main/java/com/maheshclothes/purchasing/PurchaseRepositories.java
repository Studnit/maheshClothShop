package com.maheshclothes.purchasing;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data repositories for the purchasing module (one file, small interfaces). */
public final class PurchaseRepositories {

    private PurchaseRepositories() {
    }

    public interface SupplierRepository extends JpaRepository<Supplier, UUID> {
        boolean existsByNameIgnoreCase(String name);
    }

    public interface PurchaseInvoiceRepository extends JpaRepository<PurchaseInvoice, UUID> {

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select p from PurchaseInvoice p where p.id = :id")
        Optional<PurchaseInvoice> lockById(@Param("id") UUID id);

        List<PurchaseInvoice> findBySupplierIdAndSupplierInvoiceNoIgnoreCaseAndStatusNot(UUID supplierId,
                                                                                        String supplierInvoiceNo,
                                                                                        PurchaseStatus status);
    }

    public interface PurchaseItemRepository extends JpaRepository<PurchaseItem, UUID> {
        List<PurchaseItem> findByPurchaseId(UUID purchaseId);

        void deleteByPurchaseId(UUID purchaseId);
    }

    public interface SupplierPaymentRepository extends JpaRepository<SupplierPayment, UUID> {
    }

    public interface SupplierReturnRepository extends JpaRepository<SupplierReturn, UUID> {
    }

    public interface SupplierReturnItemRepository extends JpaRepository<SupplierReturnItem, UUID> {
    }
}
