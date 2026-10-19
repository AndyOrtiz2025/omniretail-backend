package com.omniretail.backend.commercial;

import com.omniretail.backend.shared.security.SaasCapability;
import java.util.Arrays;
import java.util.List;

/**
 * Listas permitidas del formulario. Los modulos son las capacidades reales del producto
 * ({@link SaasCapability}); los rubros son una lista cerrada que la landing debe usar tal cual.
 */
final class ContactCatalog {

    static final List<String> INDUSTRIES =
            List.of("retail", "wholesale", "hardware", "pharmacy", "grocery", "services", "other");

    static final List<String> MODULES = Arrays.stream(SaasCapability.values())
            .map(SaasCapability::getKey)
            .toList();

    private ContactCatalog() {
    }
}
