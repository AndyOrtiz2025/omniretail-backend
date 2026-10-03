package com.omniretail.backend.inventory.repository;

import com.omniretail.backend.inventory.entity.InventoryLot;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface InventoryLotRepository extends JpaRepository<InventoryLot, UUID> {

    Optional<InventoryLot> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<InventoryLot> findByTenantIdAndProductIdAndLotNumber(
            UUID tenantId, UUID productId, String lotNumber);

    List<InventoryLot> findByTenantIdAndIdIn(UUID tenantId, List<UUID> ids);

    @Modifying
    @Query(
            value = """
                    INSERT INTO inventory_lots
                        (id, tenant_id, product_id, lot_number, expiration_date)
                    VALUES (:id, :tenantId, :productId, :lotNumber, :expirationDate)
                    ON CONFLICT ON CONSTRAINT uk_inventory_lots_tenant_product_number DO NOTHING
                    """,
            nativeQuery = true)
    void ensureExists(
            UUID id,
            UUID tenantId,
            UUID productId,
            String lotNumber,
            LocalDate expirationDate);

    List<InventoryLot> findByTenantIdAndProductIdOrderByExpirationDateAscLotNumberAsc(
            UUID tenantId, UUID productId);

    List<InventoryLot> findByTenantIdAndProductIdAndExpirationDateBetweenOrderByExpirationDateAscLotNumberAsc(
            UUID tenantId, UUID productId, LocalDate from, LocalDate to);
}
