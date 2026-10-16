package com.maheshclothes.sales;

import com.maheshclothes.common.AuditService;
import com.maheshclothes.common.BusinessException;
import com.maheshclothes.common.Money;
import com.maheshclothes.common.NotFoundException;
import com.maheshclothes.common.NumberingService;
import com.maheshclothes.common.PhoneNumbers;
import com.maheshclothes.sales.SalesRepositories.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customers;
    private final NumberingService numbering;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    public record CustomerInput(String name, String phone, String address, String notes, BigDecimal creditLimit,
                                boolean active) {
    }

    public Customer get(UUID id) {
        return customers.findById(id).orElseThrow(() -> new NotFoundException("Customer"));
    }

    /** What the customer owes us across all completed invoices. */
    public BigDecimal outstanding(UUID customerId) {
        BigDecimal v = jdbc.queryForObject("select coalesce(sum(balance_due),0) from sales_invoices "
                + "where customer_id = ? and status = 'COMPLETED'", BigDecimal.class, customerId);
        return v == null ? Money.ZERO : v;
    }

    @Transactional
    public Customer save(UUID id, CustomerInput in) {
        if (in.name() == null || in.name().isBlank()) {
            throw new BusinessException("customer.error.name");
        }
        String phone = null;
        if (in.phone() != null && !in.phone().isBlank()) {
            phone = PhoneNumbers.toWhatsApp(in.phone(), "91");
            if (phone == null) {
                throw new BusinessException("customer.error.phone");
            }
            phone = phone.length() == 12 && phone.startsWith("91") ? phone.substring(2) : phone;
            boolean taken = id == null ? customers.findByPhone(phone).isPresent()
                    : customers.existsByPhoneAndIdNot(phone, id);
            if (taken) {
                throw new BusinessException("customer.error.phone.duplicate");
            }
        }
        if (in.creditLimit() != null && in.creditLimit().signum() < 0) {
            throw new BusinessException("customer.error.creditlimit");
        }
        Customer c;
        if (id == null) {
            c = new Customer();
            c.setCustomerCode(numbering.nextCode("CUSTOMER", "C", 5));
        } else {
            c = get(id);
        }
        c.setName(in.name().trim());
        c.setPhone(phone);
        c.setAddress(blank(in.address()));
        c.setNotes(blank(in.notes()));
        c.setCreditLimit(in.creditLimit() == null ? null : Money.scale(in.creditLimit()));
        c.setActive(in.active());
        c = customers.save(c);
        audit.log(id == null ? "CUSTOMER_CREATED" : "CUSTOMER_UPDATED", "customer", c.getId(), c.getCustomerCode() + " " + c.getName());
        return c;
    }

    /** Quick-add from the billing screen. Returns the existing customer when the phone is already registered. */
    @Transactional
    public Customer quickAdd(String name, String phone) {
        if (phone != null && !phone.isBlank()) {
            String p = PhoneNumbers.toWhatsApp(phone, "91");
            if (p != null) {
                String local = p.length() == 12 && p.startsWith("91") ? p.substring(2) : p;
                var existing = customers.findByPhone(local);
                if (existing.isPresent()) {
                    return existing.get();
                }
            }
        }
        return save(null, new CustomerInput(name, phone, null, null, null, true));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
