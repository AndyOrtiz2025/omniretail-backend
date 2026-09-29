package com.omniretail.backend.pos.service;

import com.omniretail.backend.pos.entity.DocumentCounter;
import com.omniretail.backend.pos.repository.DocumentCounterRepository;
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

    private String nextNumber(UUID tenantId, String counterKey, String prefix, String label) {
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

        return prefix + String.format(Locale.ROOT, "%03d", nextValue);
    }
}
