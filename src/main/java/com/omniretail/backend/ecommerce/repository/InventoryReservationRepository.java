package com.omniretail.backend.ecommerce.repository;

import com.omniretail.backend.ecommerce.entity.InventoryReservation;
import com.omniretail.backend.ecommerce.entity.InventoryReservationSourceType;
import com.omniretail.backend.ecommerce.entity.InventoryReservationStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryReservationRepository extends JpaRepository<InventoryReservation, UUID> {

    List<InventoryReservation> findByTenantIdAndOrderId(UUID tenantId, UUID orderId);

    List<InventoryReservation> findByTenantIdAndSourceTypeAndSourceId(
            UUID tenantId, InventoryReservationSourceType sourceType, UUID sourceId);

    List<InventoryReservation> findByTenantIdAndSourceTypeAndSourceIdAndStatus(
            UUID tenantId,
            InventoryReservationSourceType sourceType,
            UUID sourceId,
            InventoryReservationStatus status);
}
