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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<InventorySerial> findForUpdateByTenantIdAndId(UUID tenantId, UUID id);

    /**
     * true si el producto tiene series aun en inventario (disponibles, reservadas o en transito) en la
     * sucursal fuera de la ubicacion indicada (null = sin ubicacion). Solo lectura.
     */
    @Query(
            value = """
                    SELECT EXISTS (
                        SELECT 1
                        FROM inventory_serials inv_serial
                        WHERE inv_serial.tenant_id = :tenantId
                          AND inv_serial.branch_id = :branchId
                          AND inv_serial.product_id = :productId
                          AND inv_serial.status IN ('AVAILABLE', 'RESERVED', 'IN_TRANSIT')
                          AND inv_serial.location_id IS DISTINCT FROM CAST(:locationId AS uuid)
                    )
                    """,
            nativeQuery = true)
    boolean existsInStockOutsideLocation(
            @Param("tenantId") UUID tenantId,
            @Param("branchId") UUID branchId,
            @Param("productId") UUID productId,
            @Param("locationId") UUID locationId);

    Optional<InventorySerial> findByTenantIdAndProductIdAndSerialNumber(
            UUID tenantId, UUID productId, String serialNumber);

    List<InventorySerial> findByTenantIdAndProductIdAndSerialNumberIn(
            UUID tenantId, UUID productId, List<String> serialNumbers);

    @Query("""
            select serial.id from InventorySerial serial
            where serial.tenantId = :tenantId
              and serial.productId = :productId
              and serial.serialNumber in :serialNumbers
            order by serial.serialNumber asc, serial.id asc
            """)
    List<UUID> findIdsByTenantIdAndProductIdAndSerialNumberIn(
            @Param("tenantId") UUID tenantId,
            @Param("productId") UUID productId,
            @Param("serialNumbers") List<String> serialNumbers);

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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select serial from InventorySerial serial
            where serial.tenantId = :tenantId
              and serial.branchId = :branchId
              and serial.productId = :productId
              and serial.locationId = :locationId
              and serial.status in :statuses
            order by serial.serialNumber
            """)
    List<InventorySerial> findPhysicalForUpdateAtLocation(
            @Param("tenantId") UUID tenantId,
            @Param("branchId") UUID branchId,
            @Param("productId") UUID productId,
            @Param("locationId") UUID locationId,
            @Param("statuses") Collection<InventorySerialStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select serial from InventorySerial serial
            where serial.tenantId = :tenantId
              and serial.branchId = :branchId
              and serial.productId = :productId
              and serial.locationId is null
              and serial.status in :statuses
            order by serial.serialNumber
            """)
    List<InventorySerial> findPhysicalForUpdateWithoutLocation(
            @Param("tenantId") UUID tenantId,
            @Param("branchId") UUID branchId,
            @Param("productId") UUID productId,
            @Param("statuses") Collection<InventorySerialStatus> statuses);

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
