package com.omniretail.backend.inventory.repository;

import com.omniretail.backend.inventory.entity.InventoryBalance;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface InventoryBalanceRepository extends JpaRepository<InventoryBalance, UUID> {

    Page<InventoryBalance> findByTenantIdAndBranchId(
            UUID tenantId, UUID branchId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<InventoryBalance> findByTenantIdAndBranchIdAndProductIdAndLocationIdIsNull(
            UUID tenantId, UUID branchId, UUID productId);
}
