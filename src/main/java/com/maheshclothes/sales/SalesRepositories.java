package com.maheshclothes.sales;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class SalesRepositories {

    private SalesRepositories() {
    }

    public interface CustomerRepository extends JpaRepository<Customer, UUID> {
        Optional<Customer> findByPhone(String phone);

        boolean existsByPhoneAndIdNot(String phone, UUID id);

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select c from Customer c where c.id = :id")
        Optional<Customer> lockById(@Param("id") UUID id);
    }

    public interface PaymentMethodRepository extends JpaRepository<PaymentMethod, String> {
        List<PaymentMethod> findByActiveTrueAndSelectableTrueOrderBySortOrder();
    }

    public interface SalesInvoiceRepository extends JpaRepository<SalesInvoice, UUID> {
        Optional<SalesInvoice> findByClientRequestId(UUID clientRequestId);

        Optional<SalesInvoice> findByInvoiceNo(String invoiceNo);

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select i from SalesInvoice i where i.id = :id")
        Optional<SalesInvoice> lockById(@Param("id") UUID id);
    }

    public interface SalesItemRepository extends JpaRepository<SalesItem, UUID> {
        List<SalesItem> findByInvoiceIdOrderByCreatedAt(UUID invoiceId);

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select i from SalesItem i where i.invoiceId = :invoiceId order by i.id")
        List<SalesItem> lockByInvoiceId(@Param("invoiceId") UUID invoiceId);
    }

    public interface SalesPaymentRepository extends JpaRepository<SalesPayment, UUID> {
        List<SalesPayment> findByInvoiceIdOrderByReceivedAt(UUID invoiceId);
    }
}
