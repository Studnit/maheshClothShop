package com.maheshclothes.sales;

import java.util.UUID;

/** Published inside the sale transaction; listeners that talk to the outside world run AFTER_COMMIT. */
public record SaleCompletedEvent(UUID invoiceId, String invoiceNo, UUID customerId, String customerName,
                                 String customerPhone, boolean sendWhatsApp) {
}
