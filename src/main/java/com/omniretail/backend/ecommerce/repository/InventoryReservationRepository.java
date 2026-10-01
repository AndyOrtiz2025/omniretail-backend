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

public interface InventoryReservationRepository extends JpaRepository<InventoryReservation, UUID> {

    List<InventoryReservation> findByTenantIdAndOrderId(UUID tenantId, UUID orderId);

    List<InventoryReservation> findByTenantIdAndSourceTypeAndSourceId(
            UUID tenantId, InventoryReservationSourceType sourceType, UUID sourceId);

    List<InventoryReservation> findByTenantIdAndSourceTypeAndSourceIdAndStatus(
            UUID tenantId,
            InventoryReservationSourceType sourceType,
            UUID sourceId,
            InventoryReservationStatus status);

    boolean existsByTenantIdAndSourceTypeAndSourceLineId(
            UUID tenantId, InventoryReservationSourceType sourceType, UUID sourceLineId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<InventoryReservation> findByTenantIdAndId(UUID tenantId, UUID id);
}
