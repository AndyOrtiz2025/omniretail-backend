package com.omniretail.backend.pos.repository;

import com.omniretail.backend.pos.entity.CashShift;
import com.omniretail.backend.pos.entity.CashShiftStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CashShiftRepository extends JpaRepository<CashShift, UUID> {

    Optional<CashShift> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<CashShift> findByTenantIdAndBranchIdAndUserIdAndStatus(
            UUID tenantId, UUID branchId, UUID userId, CashShiftStatus status);
}
