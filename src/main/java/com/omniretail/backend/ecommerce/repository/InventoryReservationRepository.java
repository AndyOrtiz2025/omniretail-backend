package com.omniretail.backend.ecommerce.repository;

import com.omniretail.backend.ecommerce.entity.InventoryReservation;
import com.omniretail.backend.ecommerce.entity.InventoryReservationSourceType;
import com.omniretail.backend.ecommerce.entity.InventoryReservationStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryReservationRepository extends JpaRepository<InventoryReservation, UUID> {

    List<InventoryReservation> findByTenantIdAndOrderId(UUID tenantId, UUID orderId);

    List<InventoryReservation> findByTenantIdAndSourceTypeAndSourceId(
            UUID tenantId, InventoryReservationSourceType sourceType, UUID sourceId);

    List<InventoryReservation> findByTenantIdAndSourceTypeAndSourceIdAndStatus(
            UUID tenantId,
            InventoryReservationSourceType sourceType,
            UUID sourceId,
            InventoryReservationStatus status);

    Optional<InventoryReservation> findByTenantIdAndSourceTypeAndSourceLineIdAndProductIdAndStatus(
            UUID tenantId,
            InventoryReservationSourceType sourceType,
            UUID sourceLineId,
            UUID productId,
            InventoryReservationStatus status);

    boolean existsByTenantIdAndSourceTypeAndSourceLineIdAndProductId(
            UUID tenantId, InventoryReservationSourceType sourceType, UUID sourceLineId, UUID productId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<InventoryReservation> findByTenantIdAndId(UUID tenantId, UUID id);

    /** Reservas activas del producto en la sucursal, ordenadas por id (lectura sin bloqueo). */
    @Query("""
            select reservation from InventoryReservation reservation
            where reservation.tenantId = :tenantId
              and reservation.branchId = :branchId
              and reservation.productId = :productId
              and reservation.status = :status
            order by reservation.id
            """)
    List<InventoryReservation> findByScopeAndStatusOrderById(
            @Param("tenantId") UUID tenantId,
            @Param("branchId") UUID branchId,
            @Param("productId") UUID productId,
            @Param("status") InventoryReservationStatus status);

    @Query("""
            select reservation from InventoryReservation reservation
            where reservation.tenantId = :tenantId
              and reservation.id = :id
            """)
    Optional<InventoryReservation> findSnapshotByTenantIdAndId(
            @Param("tenantId") UUID tenantId, @Param("id") UUID id);
}
