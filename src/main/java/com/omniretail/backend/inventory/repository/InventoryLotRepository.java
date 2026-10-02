package com.omniretail.backend.inventory.repository;

import com.omniretail.backend.inventory.entity.InventoryLot;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryLotRepository extends JpaRepository<InventoryLot, UUID> {

    Optional<InventoryLot> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<InventoryLot> findByTenantIdAndProductIdAndLotNumber(
            UUID tenantId, UUID productId, String lotNumber);

    List<InventoryLot> findByTenantIdAndProductIdOrderByExpirationDateAscLotNumberAsc(
            UUID tenantId, UUID productId);

    List<InventoryLot> findByTenantIdAndProductIdAndExpirationDateBetweenOrderByExpirationDateAscLotNumberAsc(
            UUID tenantId, UUID productId, LocalDate from, LocalDate to);
}
