package com.omniretail.backend.pos.service;

import com.omniretail.backend.pos.entity.DocumentCounter;
import com.omniretail.backend.pos.repository.DocumentCounterRepository;
import java.time.Year;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DocumentCounterService {

    private static final String POS_SALE_COUNTER_KEY = "pos_sale";
    private static final String POS_SALE_PREFIX = "POS-";
    private static final String PURCHASE_ORDER_COUNTER_KEY = "purchase_order";
    private static final String PURCHASE_ORDER_PREFIX = "OC-";
    private static final String GOODS_RECEIPT_COUNTER_KEY = "goods_receipt";
    private static final String GOODS_RECEIPT_PREFIX = "REC-";
    private static final String CUSTOMER_COUNTER_KEY = "customer";
    private static final String CUSTOMER_PREFIX = "CLI-";
    private static final String INVENTORY_TRANSFER_COUNTER_KEY_PREFIX = "inventory_transfer:";
    private static final String INVENTORY_TRANSFER_PREFIX = "TR-";
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Guatemala");

    private final DocumentCounterRepository documentCounterRepository;

    @Transactional
    public String nextPosSaleNumber(UUID tenantId) {
        return nextNumber(tenantId, POS_SALE_COUNTER_KEY, POS_SALE_PREFIX, "POS");
    }

    @Transactional
    public String nextPurchaseOrderNumber(UUID tenantId) {
        return nextNumber(
                tenantId, PURCHASE_ORDER_COUNTER_KEY, PURCHASE_ORDER_PREFIX, "de orden de compra");
    }

    @Transactional
    public String nextGoodsReceiptNumber(UUID tenantId) {
        return nextNumber(
                tenantId, GOODS_RECEIPT_COUNTER_KEY, GOODS_RECEIPT_PREFIX, "de recepcion de compra");
    }

    @Transactional
    public String nextCustomerCode(UUID tenantId) {
        return nextNumber(tenantId, CUSTOMER_COUNTER_KEY, CUSTOMER_PREFIX, "de clientes");
    }

    @Transactional
    public String nextInventoryTransferNumber(UUID tenantId) {
        int year = Year.now(BUSINESS_ZONE).getValue();
        return nextNumber(
                tenantId,
                INVENTORY_TRANSFER_COUNTER_KEY_PREFIX + year,
                INVENTORY_TRANSFER_PREFIX + year + "-",
                "de transferencias de inventario",
                5);
    }

    private String nextNumber(UUID tenantId, String counterKey, String prefix, String label) {
        return nextNumber(tenantId, counterKey, prefix, label, 3);
    }

    private String nextNumber(
            UUID tenantId, String counterKey, String prefix, String label, int minimumDigits) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId es requerido.");
        }

        documentCounterRepository.ensureExists(tenantId, counterKey);
        DocumentCounter counter = documentCounterRepository
                .findByTenantIdAndCounterKey(tenantId, counterKey)
                .orElseThrow(() -> new IllegalStateException("No se pudo inicializar el contador " + label + "."));

        long nextValue = Math.incrementExact(counter.getLastValue());
        counter.setLastValue(nextValue);
        documentCounterRepository.flush();

        return prefix + String.format(Locale.ROOT, "%0" + minimumDigits + "d", nextValue);
    }
}
