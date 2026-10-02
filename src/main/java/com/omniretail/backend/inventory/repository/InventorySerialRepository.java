package com.omniretail.backend.inventory.repository;

import com.omniretail.backend.inventory.entity.InventorySerial;
import com.omniretail.backend.inventory.entity.InventorySerialStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventorySerialRepository extends JpaRepository<InventorySerial, UUID> {

    Optional<InventorySerial> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<InventorySerial> findByTenantIdAndProductIdAndSerialNumber(
            UUID tenantId, UUID productId, String serialNumber);

    List<InventorySerial> findByTenantIdAndBranchIdAndProductIdOrderBySerialNumberAsc(
            UUID tenantId, UUID branchId, UUID productId);

    List<InventorySerial> findByTenantIdAndBranchIdAndProductIdAndStatusOrderBySerialNumberAsc(
            UUID tenantId, UUID branchId, UUID productId, InventorySerialStatus status);

    List<InventorySerial> findByTenantIdAndBranchIdAndLocationIdAndStatusOrderBySerialNumberAsc(
            UUID tenantId, UUID branchId, UUID locationId, InventorySerialStatus status);
}
