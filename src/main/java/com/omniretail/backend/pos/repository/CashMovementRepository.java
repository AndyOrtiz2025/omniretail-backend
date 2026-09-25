package com.omniretail.backend.pos.repository;

import com.omniretail.backend.pos.entity.CashMovement;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CashMovementRepository extends JpaRepository<CashMovement, UUID> {

    List<CashMovement> findByTenantIdAndCashShiftIdOrderByCreatedAtAscIdAsc(
            UUID tenantId, UUID cashShiftId);
}
