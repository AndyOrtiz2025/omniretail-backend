package com.omniretail.backend.ecommerce.repository;

import com.omniretail.backend.ecommerce.entity.InventoryReservation;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryReservationRepository extends JpaRepository<InventoryReservation, UUID> {

    List<InventoryReservation> findByTenantIdAndOrderId(UUID tenantId, UUID orderId);
}
