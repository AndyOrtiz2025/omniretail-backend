package com.omniretail.backend.inventory.repository;

import com.omniretail.backend.inventory.entity.InventorySerial;
import com.omniretail.backend.inventory.entity.InventorySerialStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventorySerialRepository extends JpaRepository<InventorySerial, UUID> {

    Optional<InventorySerial> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<InventorySerial> findByTenantIdAndProductIdAndSerialNumber(
            UUID tenantId, UUID productId, String serialNumber);

    List<InventorySerial> findByTenantIdAndProductIdAndSerialNumberIn(
            UUID tenantId, UUID productId, List<String> serialNumbers);

    @Query("""
            select serial.serialNumber from InventorySerial serial
            where serial.tenantId = :tenantId
              and serial.productId = :productId
              and serial.serialNumber in :serialNumbers
            order by serial.serialNumber
            """)
    List<String> findExistingSerialNumbers(
            @Param("tenantId") UUID tenantId,
            @Param("productId") UUID productId,
            @Param("serialNumbers") Collection<String> serialNumbers);

    List<InventorySerial> findByTenantIdAndIdIn(UUID tenantId, Collection<UUID> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select serial from InventorySerial serial
            where serial.tenantId = :tenantId
              and serial.productId = :productId
              and serial.serialNumber in :serialNumbers
            order by serial.serialNumber
            """)
    List<InventorySerial> findAllForUpdateByTenantProductAndSerialNumberIn(
            @Param("tenantId") UUID tenantId,
            @Param("productId") UUID productId,
            @Param("serialNumbers") List<String> serialNumbers);

    List<InventorySerial> findByTenantIdAndBranchIdAndProductIdOrderBySerialNumberAsc(
            UUID tenantId, UUID branchId, UUID productId);

    List<InventorySerial> findByTenantIdAndBranchIdAndProductIdAndStatusOrderBySerialNumberAsc(
            UUID tenantId, UUID branchId, UUID productId, InventorySerialStatus status);

    List<InventorySerial> findByTenantIdAndBranchIdAndLocationIdAndStatusOrderBySerialNumberAsc(
            UUID tenantId, UUID branchId, UUID locationId, InventorySerialStatus status);

    List<InventorySerial> findByTenantIdAndBranchIdAndLocationIdAndProductIdAndStatusOrderBySerialNumberAsc(
            UUID tenantId,
            UUID branchId,
            UUID locationId,
            UUID productId,
            InventorySerialStatus status);

    List<InventorySerial> findByTenantIdAndBranchIdAndProductIdAndLotIdAndStatusOrderBySerialNumberAsc(
            UUID tenantId,
            UUID branchId,
            UUID productId,
            UUID lotId,
            InventorySerialStatus status);

    List<InventorySerial> findByTenantIdAndBranchIdAndLocationIdAndProductIdAndLotIdAndStatusOrderBySerialNumberAsc(
            UUID tenantId,
            UUID branchId,
            UUID locationId,
            UUID productId,
            UUID lotId,
            InventorySerialStatus status);
}
