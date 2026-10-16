package com.maheshclothes.inventory;

/** Every reason stock can change. Stored on the append-only stock ledger. */
public enum MovementType {
    OPENING,
    PURCHASE_RECEIPT,
    SALE,
    SALE_CANCEL,
    SALE_RETURN,
    RETURN_DAMAGED,
    SUPPLIER_RETURN,
    ADJUST_IN,
    ADJUST_OUT,
    DAMAGE,
    WRITE_OFF,
    COUNT
}
