package com.omniretail.backend.inventory.repository;

import com.omniretail.backend.inventory.entity.InventoryBalance;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryBalanceRepository extends JpaRepository<InventoryBalance, UUID> {

    Page<InventoryBalance> findByTenantIdAndBranchId(
            UUID tenantId, UUID branchId, Pageable pageable);
}
