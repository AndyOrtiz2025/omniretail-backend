package com.omniretail.backend.inventory.repository;

import com.omniretail.backend.inventory.entity.InventoryBalanceRegularization;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryBalanceRegularizationRepository
        extends JpaRepository<InventoryBalanceRegularization, UUID> {

    Optional<InventoryBalanceRegularization> findByTenantIdAndIdempotencyKey(
            UUID tenantId, UUID idempotencyKey);

    /**
     * Regularizaciones del producto en la sucursal creadas despues de {@code createdAt}, de la mas antigua
     * a la mas reciente. Sirve para decidir el destino de una restauracion cuyo origen historico fue el
     * balance sin ubicacion.
     */
    List<InventoryBalanceRegularization>
            findByTenantIdAndBranchIdAndProductIdAndCreatedAtAfterOrderByCreatedAtAscIdAsc(
                    UUID tenantId, UUID branchId, UUID productId, Instant createdAt);
}
