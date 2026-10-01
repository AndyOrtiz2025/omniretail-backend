package com.omniretail.backend.ecommerce.repository;

import com.omniretail.backend.ecommerce.entity.InventoryReservation;
import java.util.List;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface InventoryReservationRepository extends JpaRepository<InventoryReservation, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<InventoryReservation> findByTenantIdAndOrderId(UUID tenantId, UUID orderId);
}
