package com.omniretail.backend.pos.repository;

import com.omniretail.backend.pos.entity.CashShift;
import com.omniretail.backend.pos.entity.CashShiftStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CashShiftRepository extends JpaRepository<CashShift, UUID> {

    Optional<CashShift> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<CashShift> findByTenantIdAndBranchIdAndUserIdAndStatus(
            UUID tenantId, UUID branchId, UUID userId, CashShiftStatus status);

    List<CashShift> findByTenantIdOrderByOpenedAtDesc(UUID tenantId);

    List<CashShift> findByTenantIdAndStatusOrderByOpenedAtDesc(UUID tenantId, CashShiftStatus status);

    List<CashShift> findByTenantIdAndBranchIdOrderByOpenedAtDesc(UUID tenantId, UUID branchId);

    List<CashShift> findByTenantIdAndBranchIdAndStatusOrderByOpenedAtDesc(
            UUID tenantId, UUID branchId, CashShiftStatus status);
}
