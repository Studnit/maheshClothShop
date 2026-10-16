package com.maheshclothes.purchasing;

import com.maheshclothes.common.AuditService;
import com.maheshclothes.common.BusinessException;
import com.maheshclothes.common.Money;
import com.maheshclothes.common.NotFoundException;
import com.maheshclothes.common.NumberingService;
import com.maheshclothes.purchasing.PurchaseRepositories.SupplierRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SupplierService {

    private final SupplierRepository suppliers;
    private final NumberingService numbering;
    private final AuditService audit;

    public record SupplierInput(String name, String phone, String address, String gstin, BigDecimal openingBalance,
                                String notes, boolean active) {
    }

    public Supplier get(UUID id) {
        return suppliers.findById(id).orElseThrow(() -> new NotFoundException("Supplier"));
    }

    @Transactional
    public Supplier save(UUID id, SupplierInput in) {
        if (in.name() == null || in.name().isBlank()) {
            throw new BusinessException("supplier.error.name");
        }
        String gstin = in.gstin() == null ? "" : in.gstin().trim().toUpperCase();
        if (!gstin.isEmpty() && !gstin.matches("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$")) {
            throw new BusinessException("supplier.error.gstin");
        }
        Supplier s;
        if (id == null) {
            s = new Supplier();
            s.setSupplierCode(numbering.nextCode("SUPPLIER", "S", 4));
            s.setOpeningBalance(Money.scale(in.openingBalance()));
        } else {
            s = get(id);
            // The opening balance is fixed once the supplier has history; corrections go through payments/returns.
            if (in.openingBalance() != null && Money.scale(in.openingBalance()).compareTo(s.getOpeningBalance()) != 0) {
                s.setOpeningBalance(Money.scale(in.openingBalance()));
            }
        }
        s.setName(in.name().trim());
        s.setPhone(blank(in.phone()));
        s.setAddress(blank(in.address()));
        s.setGstin(gstin.isEmpty() ? null : gstin);
        s.setNotes(blank(in.notes()));
        s.setActive(in.active());
        s = suppliers.save(s);
        audit.log(id == null ? "SUPPLIER_CREATED" : "SUPPLIER_UPDATED", "supplier", s.getId(), s.getSupplierCode() + " " + s.getName());
        return s;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
