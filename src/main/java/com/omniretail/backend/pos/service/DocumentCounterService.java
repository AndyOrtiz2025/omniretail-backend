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

    private final DocumentCounterRepository documentCounterRepository;

    @Transactional
    public String nextPosSaleNumber(UUID tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId es requerido.");
        }

        documentCounterRepository.ensureExists(tenantId, POS_SALE_COUNTER_KEY);
        DocumentCounter counter = documentCounterRepository
                .findByTenantIdAndCounterKey(tenantId, POS_SALE_COUNTER_KEY)
                .orElseThrow(() -> new IllegalStateException("No se pudo inicializar el contador POS."));

        long nextValue = Math.incrementExact(counter.getLastValue());
        counter.setLastValue(nextValue);
        documentCounterRepository.flush();

        return POS_SALE_PREFIX + String.format(Locale.ROOT, "%03d", nextValue);
    }
}
